/*
 * Copyright 2015, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */
package com.yahoo.sketches.pig.theta;

import static com.yahoo.sketches.pig.PigTestingUtil.LS;
import static com.yahoo.sketches.pig.PigTestingUtil.createDbaFromQssRange;
import static com.yahoo.sketches.pig.theta.PigUtil.dataByteArrayToSketch;
import static com.yahoo.sketches.pig.theta.PigUtil.tupleToSketch;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;

import org.apache.pig.Accumulator;
import org.apache.pig.EvalFunc;
import org.apache.pig.FuncSpec;
import org.apache.pig.builtin.mock.Storage;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.Util;
import com.yahoo.sketches.pig.theta.DataToSketch;
import com.yahoo.sketches.pig.theta.Estimate;
import com.yahoo.sketches.theta.Sketch;

/**
 * @author Lee Rhodes
 */
public class DataToSketchTest {
  private String udfName = "com.yahoo.sketches.pig.theta.DataToSketch";
  private long seed_ = Util.DEFAULT_UPDATE_SEED;
  
  @SuppressWarnings("unused")
  @Test(expectedExceptions = SketchesArgumentException.class)
  public void testConstructorExceptions1() {
    DataToSketch test = new DataToSketch("1023");
  }
  
  @SuppressWarnings("unused")
  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testConstructorExceptions3() {
    DataToSketch test = new DataToSketch("8");
  }
  
  @SuppressWarnings("unused")
  @Test(expectedExceptions = SketchesArgumentException.class)
  public void testConstructorExceptions4() {
    DataToSketch test = new DataToSketch("1024", "2.0");
  }
  
  @Test
  public void checkNotDBAExcep() throws IOException {
    DataToSketch inter = new DataToSketch();
    //create inputTuple and a bag, add bag to inputTuple
    Tuple inputTuple = TupleFactory.getInstance().newTuple(1);
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);
    
    Tuple innerTuple = TupleFactory.getInstance().newTuple(1);
    bag.add(innerTuple);
    inter.accumulate(inputTuple); //add empty tuple
    
    innerTuple.set(0, new Double(1.0));  //not a DBA
    inter = new DataToSketch();
    inter.accumulate(inputTuple); //add wrong type
  }
  
  @SuppressWarnings("unused")
  @Test
  public void checkConstructors() {
    
    DataToSketch inter = new DataToSketch();
    inter = new DataToSketch("1024");
    inter = new DataToSketch("1024", "1.0");
    inter = new DataToSketch("1024", "1.0", "9001");
    inter = new DataToSketch(1024, (float) 1.0, 9001);
    
    DataToSketch.Initial initial = new DataToSketch.Initial();
    initial = new DataToSketch.Initial("1024");
    initial = new DataToSketch.Initial("1024", "1.0");
    initial = new DataToSketch.Initial("1024", "1.0", "9001");

    DataToSketch.IntermediateFinal intermediate = new DataToSketch.Intermediate();
    intermediate = new DataToSketch.Intermediate("1024");
    intermediate = new DataToSketch.Intermediate("1024", "1.0");
    intermediate = new DataToSketch.Intermediate("1024", "1.0", "9001");
    intermediate = new DataToSketch.Intermediate(1024, (float) 1.0, 9001);
    
    DataToSketch.IntermediateFinal interFin = new DataToSketch.Final();
    interFin = new DataToSketch.Final("1024");
    interFin = new DataToSketch.Final("1024", "1.0");
    interFin = new DataToSketch.Final("1024", "1.0", "9001");
    interFin = new DataToSketch.Final(1024, (float) 1.0, 9001);
  }
  
  @Test
  public void testTopExec() throws IOException {
    EvalFunc<DataByteArray> func = new DataToSketch();  //empty constructor, size 4096

    Tuple inputTuple = null;
    DataByteArray result = func.exec(inputTuple);
    Sketch sketch = dataByteArrayToSketch(result, seed_);
    
    assertTrue(sketch.isEmpty());
    
    inputTuple = TupleFactory.getInstance().newTuple(1);
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);

    for (int ii = 0; ii < 64; ii++ ) {
      Tuple dataTuple = TupleFactory.getInstance().newTuple(1);
      dataTuple.set(0, ii);

      bag.add(dataTuple);
    }

    result = func.exec(inputTuple);
    assertNotNull(result);
    assertTrue(result.size() > 0);
    sketch = dataByteArrayToSketch(result, seed_);
    assertEquals(sketch.getEstimate(), 64.0, 0.0);
  }
  
  /*
   * DataToSketch <br>
   * Tests all possible data types: NULL, BYTE, INTEGER, LONG, FLOAT, DOUBLE,
   * BYTEARRAY, CHARARRAY. Tests rejection of a non-simple type.
   */
  @SuppressWarnings("unchecked") //still triggers unchecked warning
  @Test
  public void textTopExec2() throws IOException {
    TupleFactory tupleFactory = TupleFactory.getInstance();
    BagFactory bagFactory = BagFactory.getInstance();
    String[] ctorArgs = { "128" };

    EvalFunc<DataByteArray> dataUdf =
        (EvalFunc<DataByteArray>) PigContext.instantiateFuncFromSpec(new FuncSpec(udfName, ctorArgs));

    // EvalFunc<Tuple> resultUdf = (EvalFunc<Tuple>)PigContext.
    //   instantiateFuncFromSpec(new FuncSpec(resultUdfName));

    Tuple t;
    DataBag bag = bagFactory.newDefaultBag();

    bag.add(tupleFactory.newTuple()); //empty with a null
    bag.add(tupleFactory.newTuple(1)); //1 empty field

    t = tupleFactory.newTuple(1); //1
    t.set(0, new Byte((byte) 1));
    bag.add(t);

    t = tupleFactory.newTuple(1); //2
    t.set(0, new Integer(2)); //int
    bag.add(t);

    t = tupleFactory.newTuple(1); //3
    t.set(0, new Long(3));
    bag.add(t);

    t = tupleFactory.newTuple(1); //4
    t.set(0, new Float(4));
    bag.add(t);

    t = tupleFactory.newTuple(1); //5
    t.set(0, new Double(5));
    bag.add(t);

    t = tupleFactory.newTuple(1); //6
    byte[] bArr = { 1, 2, 3 };
    t.set(0, new DataByteArray(bArr));
    bag.add(t);

    t = tupleFactory.newTuple(1); //-ignore
    byte[] bArr2 = new byte[0]; //empty
    t.set(0, new DataByteArray(bArr2));
    bag.add(t);

    t = tupleFactory.newTuple(1); //7
    t.set(0, new Double( -0.0));
    bag.add(t);

    t = tupleFactory.newTuple(1); //7 duplicate
    t.set(0, new Double(0.0));
    bag.add(t);

    t = tupleFactory.newTuple(1); //8
    String s = "abcde";
    t.set(0, s);
    bag.add(t);

    t = tupleFactory.newTuple(1); //- ignore
    String s2 = ""; //empty
    t.set(0, s2);
    bag.add(t);

    Tuple in = tupleFactory.newTuple(1);
    in.set(0, bag);

    //should return a sketch
    DataByteArray result = dataUdf.exec(in);

    assertNotNull(result);
    assertTrue(result.size() > 0);
    Sketch sketch = dataByteArrayToSketch(result, seed_);
    assertEquals(sketch.getEstimate(), 8.0, 0.0);
  }
 
  @SuppressWarnings("unchecked") //still triggers unchecked warning
  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testRejectionOfNonSimpleType() throws IOException {
    TupleFactory mTupleFactory = TupleFactory.getInstance();
    BagFactory bagFactory = BagFactory.getInstance();
    
    Tuple outerTuple = mTupleFactory.newTuple(1);
    DataBag outerBag = bagFactory.newDefaultBag();
    Tuple innerTuple = mTupleFactory.newTuple(1);
    DataBag innerBag = bagFactory.newDefaultBag();
    innerTuple.set(0, innerBag);
    outerBag.add(innerTuple);
    outerTuple.set(0, outerBag);
    
    String[] ctorArgs = { "128" };
    EvalFunc<DataByteArray> dataUdf =
        (EvalFunc<DataByteArray>) PigContext.instantiateFuncFromSpec(new FuncSpec(udfName, ctorArgs));
    dataUdf.exec(outerTuple);
  }
  
  @Test
  public void testAccumulate() throws IOException {
    Accumulator<DataByteArray> func = new DataToSketch("128");

    Tuple inputTuple = TupleFactory.getInstance().newTuple(1);
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);

    for (int ii = 0; ii < 64; ii++ ) {
      Tuple dataTuple = TupleFactory.getInstance().newTuple(1);
      dataTuple.set(0, ii);

      bag.add(dataTuple);
    }

    func.accumulate(inputTuple);

    inputTuple = TupleFactory.getInstance().newTuple(1);
    bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);

    for (int ii = 0; ii < 27; ii++ ) {
      Tuple dataTuple = TupleFactory.getInstance().newTuple(1);
      dataTuple.set(0, 64 + ii);

      bag.add(dataTuple);
    }
    func.accumulate(inputTuple);

    DataByteArray result = func.getValue();
    assertNotNull(result);
    assertTrue(result.size() > 0);
    Sketch sketch = dataByteArrayToSketch(result, seed_);
    assertEquals(sketch.getEstimate(), 91.0, 0.0);

    // after cleanup, the value should always be 0
    func.cleanup();
    result = func.getValue();
    assertNotNull(result);
    assertTrue(result.size() > 0);
    sketch = dataByteArrayToSketch(result, seed_);
    assertEquals(sketch.getEstimate(), 0.0, 0.0);
  }
  
  @Test
  public void testInitial() throws IOException {
    EvalFunc<Tuple> func = new DataToSketch.Initial("128");
    Tuple inputTuple = TupleFactory.getInstance().newTuple(1);
    
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);

    for (int ii = 0; ii < 64; ii++ ) {
      Tuple dataTuple = TupleFactory.getInstance().newTuple(1);
      dataTuple.set(0, ii);

      bag.add(dataTuple);
    }

    Tuple resultTuple = func.exec(inputTuple);

    assertNotNull(resultTuple);
    assertEquals(resultTuple.size(), 1);
    DataBag resultBag = (DataBag) resultTuple.get(0);
    assertEquals(resultBag.size(), 64);
  }
  
  @Test
  public void testIntermediate() throws IOException {
    EvalFunc<Tuple> func = new DataToSketch.Intermediate("128");
    
    Tuple inputTuple = null;
    Tuple resultTuple = func.exec(inputTuple);
    Sketch sketch = tupleToSketch(resultTuple, seed_);
    assertTrue(sketch.isEmpty());
    
    inputTuple = TupleFactory.getInstance().newTuple(0);
    resultTuple = func.exec(inputTuple);
    sketch = tupleToSketch(resultTuple, seed_);
    assertTrue(sketch.isEmpty());
    
    
    inputTuple = TupleFactory.getInstance().newTuple(1);
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);

    Tuple contentsTuple = TupleFactory.getInstance().newTuple(1);
    DataBag contentsBag = BagFactory.getInstance().newDefaultBag();
    contentsTuple.set(0, contentsBag);

    for (int ii = 0; ii < 40; ii++ ) {
      Tuple dataTuple = TupleFactory.getInstance().newTuple(1);
      dataTuple.set(0, ii);

      contentsBag.add(dataTuple);
    }

    Tuple intermediateTuple = TupleFactory.getInstance().newTuple(1);
    intermediateTuple.set(0, createDbaFromQssRange(64, 40, 60));

    bag.add(contentsTuple);
    bag.add(intermediateTuple);

    resultTuple = func.exec(inputTuple);

    assertNotNull(resultTuple);
    assertEquals(resultTuple.size(), 1);
    DataByteArray bytes = (DataByteArray) resultTuple.get(0);
    assertTrue(bytes.size() > 0);
    sketch = tupleToSketch(resultTuple, seed_);
    assertEquals(sketch.getEstimate(), 100.0, 0.0);
  }

  @Test
  public void testFinal() throws IOException {
    EvalFunc<DataByteArray> func = new DataToSketch.Final("128");

    Tuple inputTuple = null;
    DataByteArray result = func.exec(inputTuple);
    Sketch sketch = dataByteArrayToSketch(result, seed_);
    assertTrue(sketch.isEmpty());

    inputTuple = TupleFactory.getInstance().newTuple(0);
    result = func.exec(inputTuple);
    sketch = dataByteArrayToSketch(result, seed_);
    assertTrue(sketch.isEmpty());


    inputTuple = TupleFactory.getInstance().newTuple(1);
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);

    Tuple contentsTuple = TupleFactory.getInstance().newTuple(1);
    DataBag contentsBag = BagFactory.getInstance().newDefaultBag();
    contentsTuple.set(0, contentsBag);

    for (int ii = 0; ii < 40; ii++ ) {
      Tuple dataTuple = TupleFactory.getInstance().newTuple(1);
      dataTuple.set(0, ii);

      contentsBag.add(dataTuple);
    }

    Tuple intermediateTuple = TupleFactory.getInstance().newTuple(1);
    intermediateTuple.set(0, createDbaFromQssRange(64, 40, 60));

    bag.add(contentsTuple);
    bag.add(intermediateTuple);

    result = func.exec(inputTuple);

    assertNotNull(result);
    assertTrue(result.size() > 0);
    sketch = dataByteArrayToSketch(result, seed_);
    assertEquals(sketch.getEstimate(), 100.0, 0.0);
  }
  
  @Test
  public void checkAlgFinalOuterBagEmptyTuples() throws IOException {
    EvalFunc<Tuple> interFuncFinal = new DataToSketch.Intermediate("256");
    EvalFunc<Double> estFunc = new Estimate();
    
    Tuple inputTuple = TupleFactory.getInstance().newTuple(1);
    Tuple resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
    
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag); //inputTuple.bag0:null
    resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
    
    Tuple innerTuple = TupleFactory.getInstance().newTuple(1);
    bag.add(innerTuple);
    resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
  }
  
  @Test
  public void checkAlgFinalInnerBagEmpty() throws IOException {
    EvalFunc<Tuple> interFuncFinal = new DataToSketch.Intermediate("256");
    EvalFunc<Double> estFunc = new Estimate();
    
    Tuple inputTuple = TupleFactory.getInstance().newTuple(1);
    Tuple resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
    
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag); //inputTuple.bag0:null
    resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
    
    Tuple innerTuple = TupleFactory.getInstance().newTuple(1);
    bag.add(innerTuple);
    DataBag bag2 = BagFactory.getInstance().newDefaultBag();
    innerTuple.set(0, bag2);
    
    resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
  }
  
  @Test(expectedExceptions = IllegalArgumentException.class)
  public void checkAlgFinalInnerNotDBA() throws IOException {
    EvalFunc<Tuple> interFuncFinal = new DataToSketch.Intermediate("256");
    EvalFunc<Double> estFunc = new Estimate();
    
    Tuple inputTuple = TupleFactory.getInstance().newTuple(1);
    Tuple resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
    
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag); //inputTuple.bag0:null
    resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
    
    Tuple innerTuple = TupleFactory.getInstance().newTuple(1);
    bag.add(innerTuple);
    innerTuple.set(0, new Double(1.0)); //not a DBA
    
    resultTuple = interFuncFinal.exec(inputTuple);
    assertEquals(estFunc.exec(resultTuple), 0.0, 0.0);
  }
  
//  @Test
//  public void outputSchemaTest() throws IOException {
//    EvalFunc<Tuple> udf = new DataToSketch("512");
//
//    Schema inputSchema = null;
//
//    Schema nullOutputSchema = null;
//
//    Schema outputSchema = null;
//
//    Schema outputInnerSchema = null;
//    Schema.FieldSchema outputOuterFs0 = null;
//    Schema.FieldSchema outputInnerFs0 = null;
//
//    //CHARARRAY is one of several possible inner types
//    inputSchema = Schema.generateNestedSchema(DataType.BAG, DataType.CHARARRAY);
//
//    nullOutputSchema = udf.outputSchema(null);
//
//    outputSchema = udf.outputSchema(inputSchema);
//    outputOuterFs0 = outputSchema.getField(0);
//
//    outputInnerSchema = outputOuterFs0.schema;
//    outputInnerFs0 = outputInnerSchema.getField(0);
//
//    Assert.assertNull(nullOutputSchema, "Should be null");
//    Assert.assertNotNull(outputOuterFs0, "outputSchema.getField(0) may not be null");
//
//    String expected = "tuple";
//    String result = DataType.findTypeName(outputOuterFs0.type);
//    Assert.assertEquals(result, expected);
//
//    expected = "bytearray";
//    Assert.assertNotNull(outputInnerFs0, "innerSchema.getField(0) may not be null");
//    result = DataType.findTypeName(outputInnerFs0.type);
//    Assert.assertEquals(result, expected);
//
//    //print schemas
//    //@formatter:off
//    StringBuilder sb = new StringBuilder();
//    sb.append("input schema: ").append(inputSchema).append(LS)
//      .append("output schema: ").append(outputSchema).append(LS)
//      .append("outputOuterFs: ").append(outputOuterFs0)
//        .append(", type: ").append(DataType.findTypeName(outputOuterFs0.type)).append(LS)
//      .append("outputInnerSchema: ").append(outputInnerSchema).append(LS)
//      .append("outputInnerFs0: ").append(outputInnerFs0)
//        .append(", type: ").append(DataType.findTypeName(outputInnerFs0.type)).append(LS);
//    println(sb.toString());
//    //@formatter:on
//    //end print schemas
//  }
  
  @Test
  @SuppressWarnings("unused")
  public void checkMisc() throws IOException  {
    DataToSketch dts = new DataToSketch("512", "1.0");
    dts = new DataToSketch("512", "1.0", "9001");
    DataToSketch.Initial dtsi = new DataToSketch.Initial("512", "1.0");
    DataToSketch.Intermediate dtsinter = new DataToSketch.Intermediate("512", "1.0");
    DataToSketch.Final dtsfinal = new DataToSketch.Final("512", "1.0");
    Tuple inputTuple = TupleFactory.getInstance().newTuple(1); //null bag
    dts.accumulate(inputTuple);
    DataByteArray result = dts.getValue();
    Sketch sketch = dataByteArrayToSketch(result, seed_);
    assertTrue(sketch.isEmpty());    
  }
  
  @Test
  public void checkSmall() throws IOException {
    EvalFunc<DataByteArray> func = new DataToSketch("32");

    Tuple inputTuple = null;
    DataByteArray result = func.exec(inputTuple);
    Sketch sketch = dataByteArrayToSketch(result, seed_);
    
    assertTrue(sketch.isEmpty());
    
    inputTuple = TupleFactory.getInstance().newTuple(1);
    DataBag bag = BagFactory.getInstance().newDefaultBag();
    inputTuple.set(0, bag);

    int u = 32;
    for (int ii = 0; ii < u; ii++ ) {
      Tuple dataTuple = TupleFactory.getInstance().newTuple(1);
      dataTuple.set(0, ii);

      bag.add(dataTuple);
    }

    result = func.exec(inputTuple);
    assertNotNull(result);
    assertTrue(result.size() > 0);
    sketch = dataByteArrayToSketch(result, seed_);
    assertEquals(sketch.getEstimate(), u, 0.0);
  }
  
  @Test
  public void printlnTest() {
    println(this.getClass().getSimpleName());
  }
  
  /**
   * @param s value to print 
   */
  static void println(String s) {
    //System.out.println(s); //disable here
  }
  
}