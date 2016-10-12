/*
 * Copyright 2016, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.pig.quantiles;

import java.io.IOException;
import java.util.Comparator;

import org.apache.pig.Accumulator;
import org.apache.pig.Algebraic;
import org.apache.pig.EvalFunc;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;

import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.ArrayOfItemsSerDe;
import com.yahoo.sketches.quantiles.ItemsSketch;
import com.yahoo.sketches.quantiles.ItemsUnion;

/**
 * Computes union of ItemsSketch.
 * To assist Pig, this class implements both the <i>Accumulator</i> and <i>Algebraic</i> interfaces.
 * @param <T> type of item
 */
public abstract class UnionItemsSketch<T> extends EvalFunc<Tuple> implements Accumulator<Tuple>, Algebraic {

  private static final TupleFactory tupleFactory_ = TupleFactory.getInstance();

  // With the single exception of the Accumulator interface, UDFs are stateless.
  // All parameters kept at the class level must be final, except for the accumUnion.
  private final int k_;
  private final Comparator<T> comparator_;
  private final ArrayOfItemsSerDe<T> serDe_;
  private ItemsUnion<T> accumUnion_;

  //TOP LEVEL API

  /**
   * Base constructor.
   * 
   * @param k parameter that determines the accuracy and size of the sketch.
   * The value of 0 means the default k, whatever it is in the sketches-core library
   * @param comparator for items of type T
   * @param serDe an instance of ArrayOfItemsSerDe for type T
   */
  public UnionItemsSketch(final int k, final Comparator<T> comparator, final ArrayOfItemsSerDe<T> serDe) {
    super();
    k_ = k;
    comparator_ = comparator;
    serDe_ = serDe;
  }

  //@formatter:off
  /**
   * Top-level exec function.
   * This method accepts an input Tuple containing a Bag of one or more inner <b>Sketch Tuples</b>
   * and returns a single <b>Sketch</b> as a <b>Sketch Tuple</b>.
   * 
   * <p>If a large number of calls is anticipated, leveraging either the <i>Algebraic</i> or
   * <i>Accumulator</i> interfaces is recommended. Pig normally handles this automatically.
   * 
   * <p>Internally, this method presents the inner <b>Sketch Tuples</b> to a new <b>Union</b>. 
   * The result is returned as a <b>Sketch Tuple</b>
   * 
   * <p>Types below are in the form: Java data type: Pig DataType
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
   * @return Sketch Tuple. If inputTuple is null or empty, returns empty sketch.
   * @see "org.apache.pig.EvalFunc.exec(org.apache.pig.data.Tuple)"
   */
  //@formatter:on

  @Override // TOP LEVEL EXEC
  public Tuple exec(final Tuple inputTuple) throws IOException {
    //The exec is a stateless function.  It operates on the input and returns a result.
    if (inputTuple != null && inputTuple.size() > 0) {
      final ItemsUnion<T> union = k_ > 0 ? ItemsUnion.getInstance(k_, comparator_) : ItemsUnion.getInstance(comparator_);
      final DataBag bag = (DataBag) inputTuple.get(0);
      updateUnion(bag, union, comparator_, serDe_);
      final ItemsSketch<T> resultSketch = union.getResultAndReset();
      if (resultSketch != null) return tupleFactory_.newTuple(new DataByteArray(resultSketch.toByteArray(serDe_)));
    }
    // return empty sketch
    final ItemsSketch<T> sketch = k_ > 0 ? ItemsSketch.getInstance(k_, comparator_) : ItemsSketch.getInstance(comparator_);
    return tupleFactory_.newTuple(new DataByteArray(sketch.toByteArray(serDe_)));
  }

  @Override
  public Schema outputSchema(final Schema input) {
    if (input == null) return null;
    try {
      final Schema tupleSchema = new Schema();
      tupleSchema.add(new Schema.FieldSchema("Sketch", DataType.BYTEARRAY));
      return new Schema(new Schema.FieldSchema(getSchemaName(
          this.getClass().getName().toLowerCase(), input), tupleSchema, DataType.TUPLE));
    } catch (FrontendException e) {
      throw new RuntimeException(e);
    }
  }

  //ACCUMULATOR INTERFACE

  /**
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
  public void accumulate(final Tuple inputTuple) throws IOException {
    if (inputTuple == null || inputTuple.size() == 0) return;
    final DataBag bag = (DataBag) inputTuple.get(0);
    if (bag == null) return;
    if (accumUnion_ == null) accumUnion_ = k_ > 0 ? ItemsUnion.getInstance(k_, comparator_) : ItemsUnion.getInstance(comparator_);
    updateUnion(bag, accumUnion_, comparator_, serDe_);
  }

  /**
   * Returns the result of the Union that has been built up by multiple calls to {@link #accumulate}.
   * 
   * @return Sketch Tuple. (see {@link #exec} for return tuple format)
   * @see "org.apache.pig.Accumulator.getValue()"
   */
  @Override
  public Tuple getValue() {
    if (accumUnion_ != null) {
      final ItemsSketch<T> resultSketch = accumUnion_.getResultAndReset();
      if (resultSketch != null) return tupleFactory_.newTuple(new DataByteArray(resultSketch.toByteArray(serDe_)));
    }
    // return empty sketch
    final ItemsSketch<T> sketch = k_ > 0 ? ItemsSketch.getInstance(k_, comparator_) : ItemsSketch.getInstance(comparator_);
    return tupleFactory_.newTuple(new DataByteArray(sketch.toByteArray(serDe_)));
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

  //TOP LEVEL PRIVATE STATIC METHODS  
  
  /**
   * Updates a union given a bag of sketches
   * 
   * @param bag A bag of sketchTuples.
   * @param union The union to update
   */
  private static <T> void updateUnion(final DataBag bag, final ItemsUnion<T> union, 
        final Comparator<T> comparator, final ArrayOfItemsSerDe<T> serDe) throws ExecException {
    for (Tuple innerTuple: bag) {
      final Object f0 = innerTuple.get(0);
      if (f0 == null) continue;
      if (f0 instanceof DataByteArray) {
        final DataByteArray dba = (DataByteArray) f0;
        if (dba.size() > 0) {
          union.update(ItemsSketch.getInstance(new NativeMemory(dba.get()), comparator, serDe));
        }
      } else {
        throw new IllegalArgumentException("Field type was not DataType.BYTEARRAY: " + innerTuple.getType(0));
      }
    }
  }

  //STATIC Initial Class only called by Pig

  /**
   * Class used to calculate the initial pass of an Algebraic sketch operation. 
   * 
   * <p>
   * The Initial class simply passes through all records unchanged so that they can be
   * processed by the intermediate processor instead.</p>
   */
  public static class UnionItemsSketchInitial extends EvalFunc<Tuple> {
    // The Algebraic worker classes (Initial, IntermediateFinal) are static and stateless. 
    // The constructors must mirror the main UDF class
    /**
     * Default constructor.
     */
    public UnionItemsSketchInitial() {}

    /**
     * Constructor with specific k
     * @param kStr string representation of k
     */
    public UnionItemsSketchInitial(final String kStr) {}

    @Override
    public Tuple exec(final Tuple inputTuple) throws IOException {
      return inputTuple;
    }
  }

  // STATIC IntermediateFinal Class only called by Pig

  /**
   * Class used to calculate the intermediate or final combiner pass of an <i>Algebraic</i> union 
   * operation. This is called from the combiner, and may be called multiple times (from the mapper 
   * and from the reducer). It will receive a bag of values returned by either the <i>Intermediate</i> 
   * stage or the <i>Initial</i> stages, so it needs to be able to differentiate between and 
   * interpret both types.
   * @param <T> type of item
   */
  public static abstract class UnionItemsSketchIntermediateFinal<T> extends EvalFunc<Tuple> {
    // The Algebraic worker classes (Initial, IntermediateFinal) are static and stateless. 
    // The constructors of the concrete class must mirror the ones in the main UDF class
    private final int k_;
    private final Comparator<T> comparator_;
    private final ArrayOfItemsSerDe<T> serDe_;

    /**
     * Constructor for the intermediate and final passes of an Algebraic function.
     * 
     * @param k parameter that determines the accuracy and size of the sketch.
     * @param comparator for items of type T
     * @param serDe an instance of ArrayOfItemsSerDe for type T
     */
    public UnionItemsSketchIntermediateFinal(final int k, final Comparator<T> comparator, final ArrayOfItemsSerDe<T> serDe) {
      super();
      k_ = k;
      comparator_ = comparator;
      serDe_ = serDe;
    }

    @Override // IntermediateFinal exec
    public Tuple exec(final Tuple inputTuple) throws IOException {
      if (inputTuple != null && inputTuple.size() > 0) {
        final ItemsUnion<T> union = k_ > 0 ? ItemsUnion.getInstance(k_, comparator_) : ItemsUnion.getInstance(comparator_);
        final DataBag outerBag = (DataBag) inputTuple.get(0);
        for (final Tuple dataTuple: outerBag) {
          final Object f0 = dataTuple.get(0);
          if (f0 == null) continue;
          if (f0 instanceof DataBag) {
            final DataBag innerBag = (DataBag) f0; //inputTuple.bag0.dataTupleN.f0:bag
            if (innerBag.size() == 0) continue;
            // If field 0 of a dataTuple is again a Bag all tuples of this inner bag
            // will be passed into the union.
            // It is due to system bagged outputs from multiple mapper Initial functions.  
            // The Intermediate stage was bypassed.
            updateUnion(innerBag, union, comparator_, serDe_);
          } else if (f0 instanceof DataByteArray) { //inputTuple.bag0.dataTupleN.f0:DBA
            // If field 0 of a dataTuple is a DataByteArray we assume it is a sketch from a prior call
            // It is due to system bagged outputs from multiple mapper Intermediate functions.
            // Each dataTuple.DBA:sketch will merged into the union.
            final DataByteArray dba = (DataByteArray) f0;
            union.update(ItemsSketch.getInstance(new NativeMemory(dba.get()), comparator_, serDe_));
          } else {
            throw new IllegalArgumentException("dataTuple.Field0: Is not a DataByteArray: "
              + f0.getClass().getName());
          }
        }
        final ItemsSketch<T> resultSketch = union.getResultAndReset();
        if (resultSketch != null) return tupleFactory_.newTuple(new DataByteArray(resultSketch.toByteArray(serDe_)));
      }
      // return empty sketch
      final ItemsSketch<T> sketch = k_ > 0 ? ItemsSketch.getInstance(k_, comparator_) : ItemsSketch.getInstance(comparator_);
      return tupleFactory_.newTuple(new DataByteArray(sketch.toByteArray(serDe_)));
    }
  } // end IntermediateFinal
  
}
