/*
 * Copyright 2015, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.pig.theta;

import static com.yahoo.sketches.Util.DEFAULT_NOMINAL_ENTRIES;
import static com.yahoo.sketches.Util.DEFAULT_UPDATE_SEED;
import static com.yahoo.sketches.Util.checkIfPowerOf2;
import static com.yahoo.sketches.Util.checkProbability;
import static com.yahoo.sketches.pig.theta.PigUtil.RF;
import static com.yahoo.sketches.pig.theta.PigUtil.compactOrderedSketchToTuple;
import static com.yahoo.sketches.pig.theta.PigUtil.emptySketchTuple;
import static com.yahoo.sketches.pig.theta.PigUtil.extractBag;
import static com.yahoo.sketches.pig.theta.PigUtil.extractFieldAtIndex;
import static com.yahoo.sketches.pig.theta.PigUtil.extractTypeAtIndex;

import java.io.IOException;

import org.apache.pig.Accumulator;
import org.apache.pig.Algebraic;
import org.apache.pig.EvalFunc;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;

import com.yahoo.sketches.Util;
import com.yahoo.memory.Memory;
import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.theta.CompactSketch;
import com.yahoo.sketches.theta.SetOperation;

/**
 * This is a Pig UDF that performs the Union Set Operation on Sketches. 
 * To assist Pig, this class implements both the <i>Accumulator</i> and <i>Algebraic</i> interfaces.
 * 
 * @author Lee Rhodes
 */
public class Union extends EvalFunc<Tuple> implements Accumulator<Tuple>, Algebraic {
  //With the single exception of the Accumulator interface, UDFs are stateless.
  //All parameters kept at the class level must be final, except for the accumUpdateSketch.
  private final int nomEntries_;
  private final float p_;
  private final long seed_;
  private final Tuple emptyCompactOrderedSketchTuple_;
  private com.yahoo.sketches.theta.Union accumUnion_;
  
  //TOP LEVEL API
  
  /**
   * Default constructor to make pig validation happy.  Assumes:
   * <ul>
   * <li><a href="{@docRoot}/resources/dictionary.html#defaultNomEntries">See Default Nominal Entries</a></li>
   * <li><i>p</i> = 1.0. <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, 
   * <i>p</i></a>.</li>
   * <li><a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">See Default Update Seed</a></li>
   * </ul>
   */
  public Union() {
    this(DEFAULT_NOMINAL_ENTRIES, (float)(1.0), DEFAULT_UPDATE_SEED);
  }
  
  /**
   * String constructor. Assumes:
   * <ul>
   * <li><i>p</i> = 1.0. <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, 
   * <i>p</i></a></li>
   * <li><a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">See Default Update Seed</a></li>
   * </ul>
   * 
   * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>
   */
  public Union(String nomEntriesStr) {
    this(Integer.parseInt(nomEntriesStr), (float)(1.0), DEFAULT_UPDATE_SEED);
  }
  
  /**
   * String constructor. Assumes:
   * <ul>
   * <li><a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">See Default Update Seed</a></li>
   * </ul>
   * 
   * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>
   * @param pStr <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>.
   * Although this functionality is implemented for SketchUnions, it rarely makes sense to use it 
   * here. The proper use of upfront sampling is when building the sketches.
   */
  public Union(String nomEntriesStr, String pStr) {
    this(Integer.parseInt(nomEntriesStr), Float.parseFloat(pStr), DEFAULT_UPDATE_SEED);
  }
  
  /**
   * Full string constructor.
   * 
   * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
   * @param pStr <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>. 
   * Although this functionality is implemented for SketchUnions, it rarely makes sense to use it 
   * here. The proper use of upfront sampling is when building the sketches.
   * @param seedStr  <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
   */
  public Union(String nomEntriesStr, String pStr, String seedStr) {
    this(Integer.parseInt(nomEntriesStr), Float.parseFloat(pStr), Long.parseLong(seedStr));
  }
  
  /**
   * Base constructor.
   * 
   * @param nomEntries <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
   * @param p <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>.
   * Although this functionality is implemented for SketchUnions, it rarely makes sense to use it 
   * here. The proper use of upfront sampling is when building the sketches.
   * @param seed  <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
   */
  public Union(int nomEntries, float p, long seed) {
    super();
    this.nomEntries_ = nomEntries;
    this.p_ = p;
    this.seed_ = seed;
    this.emptyCompactOrderedSketchTuple_ = emptySketchTuple(seed);
    //Catch these errors during construction, don't wait for the exec to be called.
    checkIfPowerOf2(nomEntries, "nomEntries");
    checkProbability(p, "p");
    if (nomEntries < (1 << Util.MIN_LG_NOM_LONGS)) {
      throw new IllegalArgumentException("NomEntries too small: " + nomEntries 
          + ", required: " + (1 << Util.MIN_LG_NOM_LONGS));
    }
  }
  
  //@formatter:off
  /************************************************************************************************
   * Top-level exec function.
   * This method accepts an input Tuple containing a Bag of one or more inner <b>Sketch Tuples</b>
   * and returns a single updated <b>Sketch</b> as a <b>Sketch Tuple</b>.
   * 
   * <p>If a large number of calls are anticipated, leveraging either the <i>Algebraic</i> or
   * <i>Accumulator</i> interfaces is recommended. Pig normally handles this automatically.
   * 
   * <p>Internally, this method presents the inner <b>Sketch Tuples</b> to a new <b>Union</b>. 
   * The result is returned as a <b>Sketch Tuple</b>
   * 
   * <p><b>Input Tuple</b>
   * <ul>
   *   <li>Tuple: TUPLE (Must contain only one field)
   *     <ul>
   *       <li>index 0: DataBag: BAG (May contain 0 or more Inner Tuples)
   *         <ul>
   *           <li>index 0: Tuple: TUPLE <b>Sketch Tuple</b></li>
   *           <li>...</li>
   *           <li>index n-1: Tuple: TUPLE <b>Sketch Tuple</b></li>
   *         </ul>
   *       </li>
   *     </ul>
   *   </li>
   * </ul>
   * 
   * <b>Sketch Tuple</b>
   * <ul>
   *   <li>Tuple: TUPLE (Contains exactly 1 field)
   *     <ul>
   *       <li>index 0: DataByteArray: BYTEARRAY = The serialization of a Sketch object.</li>
   *     </ul>
   *   </li>
   * </ul>
   * 
   * @param inputTuple A tuple containing a single bag, containing Sketch Tuples.
   * @return Sketch Tuple. If inputTuple is null or empty, returns empty sketch (8 bytes).
   * @see "org.apache.pig.EvalFunc.exec(org.apache.pig.data.Tuple)"
   */
  //@formatter:on
  
  @Override //TOP LEVEL EXEC
  public Tuple exec(Tuple inputTuple) throws IOException { //throws is in API
    //The exec is a stateless function.  It operates on the input and returns a result.
    // It can only call static functions.
    com.yahoo.sketches.theta.Union union = 
        SetOperation.builder().setP(p_).setSeed(seed_).setResizeFactor(RF).buildUnion(nomEntries_);
    DataBag bag = extractBag(inputTuple);
    if (bag == null) {
      return emptyCompactOrderedSketchTuple_; //Configured with parent
    }
    
    updateUnion(bag, union);
    CompactSketch compactSketch = union.getResult(true, null);
    return compactOrderedSketchToTuple(compactSketch);
  }
  
  @Override
  public Schema outputSchema(Schema input) {
    if (input != null) {
      try {
        Schema tupleSchema = new Schema();
        tupleSchema.add(new Schema.FieldSchema("Sketch", DataType.BYTEARRAY));
        return new Schema(new Schema.FieldSchema(getSchemaName(this
            .getClass().getName().toLowerCase(), input), tupleSchema, DataType.TUPLE));
      } 
      catch (FrontendException e) {
        // fall through
      }
    }
    return null;
  }
  
  //ACCUMULATOR INTERFACE
  
  /*************************************************************************************************
   * An <i>Accumulator</i> version of the standard <i>exec()</i> method. Like <i>exec()</i>,
   * accumulator is called with a bag of Sketch Tuples. Unlike <i>exec()</i>, it doesn't serialize the
   * sketch at the end. Instead, it can be called multiple times, each time with another bag of
   * Sketch Tuples to be input to the Union.
   * 
   * @param inputTuple A tuple containing a single bag, containing Sketch Tuples.
   * @see #exec
   * @see "org.apache.pig.Accumulator.accumulate(org.apache.pig.data.Tuple)"
   * @throws IOException by Pig
   */
  @Override
  public void accumulate(Tuple inputTuple) throws IOException { //throws is in API
    if (accumUnion_ == null) { 
      accumUnion_ = 
          SetOperation.builder().setP(p_).setSeed(seed_).setResizeFactor(RF).buildUnion(nomEntries_);
    }
    DataBag bag = extractBag(inputTuple);
    if (bag == null) return;
    
    updateUnion(bag, accumUnion_);
  }

  /**
   * Returns the sketch that has been built up by multiple calls to {@link #accumulate}.
   * 
   * @return Sketch Tuple. (see {@link #exec} for return tuple format)
   * @see "org.apache.pig.Accumulator.getValue()"
   */
  @Override
  public Tuple getValue() {
    if (accumUnion_ == null) return emptyCompactOrderedSketchTuple_; //Configured with parent
    CompactSketch compactSketch = accumUnion_.getResult(true, null);
    return compactOrderedSketchToTuple(compactSketch);
  }

  /**
   * Cleans up the UDF state after being called using the {@link Accumulator} interface.
   * 
   * @see "org.apache.pig.Accumulator.cleanup()"
   */
  @Override
  public void cleanup() {
    accumUnion_ = null;
  }
  
  //ALGEBRAIC INTERFACE
  
  /*************************************************************************************************/
  @Override
  public String getInitial() {
    return Initial.class.getName();
  }

  @Override
  public String getIntermed() {
    return IntermediateFinal.class.getName();
  }

  @Override
  public String getFinal() {
    return IntermediateFinal.class.getName();
  }
  
  //TOP LEVEL PRIVATE STATIC METHODS  
  
  /*************************************************************************************************
  * Updates a union from a bag of sketches
  * 
  * @param bag A bag of sketchTuples.
  * @param union The union to update
  */
 private static void updateUnion(DataBag bag, com.yahoo.sketches.theta.Union union) {
   //Bag is not empty. process each innerTuple in the bag
   for (Tuple innerTuple : bag) {
     //validate the inner Tuples
     Object f0 = extractFieldAtIndex(innerTuple, 0);
     if (f0 == null) {
       continue;
     }
     Byte type = extractTypeAtIndex(innerTuple, 0);
     if (type == null) {
       continue;
     }
     // add only the first field of the innerTuple to the union
     if (type == DataType.BYTEARRAY) {
       DataByteArray dba = (DataByteArray) f0;
       if (dba.size() > 0) {
         union.update(new NativeMemory(dba.get()));
       }
     } 
     else {
       throw new IllegalArgumentException(
           "Field type was not DataType.BYTEARRAY: " + type);
     }
   }
 }
  
  //STATIC Initial Class only called by Pig
  
  /*************************************************************************************************
   * Class used to calculate the initial pass of an Algebraic sketch operation. 
   * 
   * <p>
   * The Initial class simply passes through all records unchanged so that they can be
   * processed by the intermediate processor instead.</p>
   */
  public static class Initial extends EvalFunc<Tuple> {
    //The Algebraic worker classes (Initial, IntermediateFinal) are static and stateless. 
    //The constructors and final parameters must mirror the parent class as there is no linkage
    // between them.
    /**
     * Default constructor to make pig validation happy.
     */
    public Initial() {
      this(Integer.toString(DEFAULT_NOMINAL_ENTRIES), "1.0", 
          Long.toString(DEFAULT_UPDATE_SEED));
    }    
    
    /**
     * Constructor for the initial pass of an Algebraic function. Pig will call this and pass the 
     * same constructor arguments as the original UDF. In this case the arguments are ignored.
     * 
     * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
     */
    public Initial(String nomEntriesStr) {
      this(nomEntriesStr, "1.0", Long.toString(DEFAULT_UPDATE_SEED));
    }
    
    /**
     * Constructor for the initial pass of an Algebraic function. Pig will call this and pass the 
     * same constructor arguments as the original UDF. In this case the arguments are ignored.
     * 
     * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
     * @param pStr <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>.
     * Although this functionality is implemented for SketchUnions, it rarely makes sense to use it 
     * here. The proper use of upfront sampling is when building the sketches.
     */
    public Initial(String nomEntriesStr, String pStr) {
      this(nomEntriesStr, pStr, Long.toString(DEFAULT_UPDATE_SEED));
    }
    
    /**
     * Constructor for the initial pass of an Algebraic function. Pig will call this and pass the 
     * same constructor arguments as the original UDF. In this case the arguments are ignored.
     * 
     * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
     * @param pStr <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>.
     * Although this functionality is implemented for SketchUnions, it rarely makes sense to use it 
     * here. The proper use of upfront sampling is when building the sketches.
     * @param seedStr <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
     */
    public Initial(String nomEntriesStr, String pStr, String seedStr) {}
    
    @Override  //Initial exec
    public Tuple exec(Tuple inputTuple) throws IOException { //throws is in API
      return inputTuple;
    }
  }
  
  // STATIC IntermediateFinal Class only called by Pig
  
  /*************************************************************************************************
   * Class used to calculate the intermediate or final combiner pass of an <i>Algebraic</i> union 
   * operation. This is called from the combiner, and may be called multiple times (from the mapper 
   * and from the reducer). It will receive a bag of values returned by either the <i>Intermediate</i> 
   * stage or the <i>Initial</i> stages, so it needs to be able to differentiate between and 
   * interpret both types.
   */
  public static class IntermediateFinal extends EvalFunc<Tuple> {
    //The Algebraic worker classes (Initial, IntermediateFinal) are static and stateless. 
    //The constructors and final parameters must mirror the parent class as there is no linkage
    // between them.
    private final int myNomEntries_;
    private final float myP_;
    private final long mySeed_;
    private final Tuple myEmptyCompactOrderedSketchTuple_;
    
    /**
     * Default constructor to make pig validation happy.  Assumes:
     * <ul>
     * <li><a href="{@docRoot}/resources/dictionary.html#defaultNomEntries">See Default Nominal Entries</a></li>
     * <li><i>p</i> = 1.0. <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, 
     * <i>p</i></a>.</li>
     * <li><a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">See Default Update Seed</a></li>
     * </ul>
     */
    public IntermediateFinal() {
      this(Integer.toString(DEFAULT_NOMINAL_ENTRIES), "1.0", 
          Long.toString(DEFAULT_UPDATE_SEED));
    }
    
    /**
     * Constructor for the intermediate and final passes of an Algebraic function. Pig will call 
     * this and pass the same constructor arguments as the base UDF.  Assumes:
     * <ul>
     * <li><i>p</i> = 1.0. <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, 
     * <i>p</i></a>.</li>
     * <li><a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">See Default Update Seed</a></li>
     * </ul>
     * 
     * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
     */
    public IntermediateFinal(String nomEntriesStr) {
      this(nomEntriesStr, "1.0", Long.toString(DEFAULT_UPDATE_SEED));
    }
    
    /**
     * Constructor for the intermediate and final passes of an Algebraic function. Pig will call 
     * this and pass the same constructor arguments as the base UDF.  Assumes:
     * <ul>
     * <li><a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">See Default Update Seed</a></li>
     * </ul>
     * 
     * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
     * @param pStr <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>.
     */
    public IntermediateFinal(String nomEntriesStr, String pStr) {
      this(nomEntriesStr, pStr, Long.toString(DEFAULT_UPDATE_SEED));
    }
    
    /**
     * Constructor with strings for the intermediate and final passes of an Algebraic function. 
     * Pig will call this and pass the same constructor arguments as the original UDF.
     * 
     * @param nomEntriesStr <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
     * @param pStr <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>.
     * @param seedStr <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
     */
    public IntermediateFinal(String nomEntriesStr, String pStr, String seedStr) {
      this(Integer.parseInt(nomEntriesStr), Float.parseFloat(pStr), Long.parseLong(seedStr));
    }

    /**
     * Constructor with primitives for the intermediate and final passes of an Algebraic function. 
     * Pig will call this and pass the same constructor arguments as the Top Level UDF.
     * 
     * @param nomEntries <a href="{@docRoot}/resources/dictionary.html#nomEntries">See Nominal Entries</a>.
     * @param p <a href="{@docRoot}/resources/dictionary.html#p">See Sampling Probability, <i>p</i></a>.
     * @param seed <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
     */
    public IntermediateFinal(int nomEntries, float p, long seed) {
      this.myNomEntries_ = nomEntries;
      this.myP_ = p;
      this.mySeed_ = seed;
      this.myEmptyCompactOrderedSketchTuple_ = emptySketchTuple(seed);
    }
    
    @Override //IntermediateFinal exec
    public Tuple exec(Tuple inputTuple) throws IOException { //throws is in API
      
      com.yahoo.sketches.theta.Union union = 
          SetOperation.builder().setP(myP_).setSeed(mySeed_).setResizeFactor(RF)
          .buildUnion(myNomEntries_);
      DataBag outerBag = extractBag(inputTuple); //InputTuple.bag0
      if (outerBag == null) {  //must have non-empty outer bag at field 0.
        return myEmptyCompactOrderedSketchTuple_;
      }
      //Bag is not empty.
      
      for (Tuple dataTuple : outerBag) {
        Object f0 = extractFieldAtIndex(dataTuple, 0); //inputTuple.bag0.dataTupleN.f0
        //must have non-null field zero
        if (f0 == null) {
          continue; //go to next dataTuple if there is one.  If none, exception is thrown.
        }
        //f0 is not null
        if (f0 instanceof DataBag) {
          DataBag innerBag = (DataBag)f0; //inputTuple.bag0.dataTupleN.f0:bag
          if (innerBag.size() == 0) {
            continue; //go to next dataTuple if there is one.  If none, exception is thrown.
          }
          //If field 0 of a dataTuple is again a Bag all tuples of this inner bag
          // will be passed into the union.
          //It is due to system bagged outputs from multiple mapper Initial functions.  
          //The Intermediate stage was bypassed.
          updateUnion(innerBag, union); //process all tuples of innerBag
          
        } 
        else if (f0 instanceof DataByteArray) { //inputTuple.bag0.dataTupleN.f0:DBA
          //If field 0 of a dataTuple is a DataByteArray we assume it is a sketch from a prior call
          //It is due to system bagged outputs from multiple mapper Intermediate functions.
          // Each dataTuple.DBA:sketch will merged into the union.
          DataByteArray dba = (DataByteArray) f0;
          Memory srcMem = new NativeMemory(dba.get());
          union.update(srcMem);
        
        } 
        else { // we should never get here.
          throw new IllegalArgumentException("dataTuple.Field0: Is not a DataByteArray: "
              + f0.getClass().getName());
        }
      }
      
      CompactSketch compactSketch = union.getResult(true, null);
      return compactOrderedSketchToTuple(compactSketch);
    }
    
  } //End IntermediateFinal
  
}