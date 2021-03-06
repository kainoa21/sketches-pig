/*
 * Copyright 2016, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */
package com.yahoo.sketches.pig.quantiles;

import org.apache.pig.Accumulator;
import org.apache.pig.EvalFunc;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.schema.Schema;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.quantiles.DoublesSketch;


public class DataToDoublesSketchTest {
  private static final TupleFactory tupleFactory = TupleFactory.getInstance();
  private static final BagFactory bagFactory = BagFactory.getInstance();

  @Test
  public void execNullInputTuple() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch();
    Tuple resultTuple = func.exec(null);
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());
  }

  @Test
  public void execEmptyInputTuple() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch();
    Tuple resultTuple = func.exec(tupleFactory.newTuple());
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());
  }

  @Test
  public void execEmptyBag() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch();
    Tuple resultTuple = func.exec(tupleFactory.newTuple(bagFactory.newDefaultBag()));
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());
  }

  @Test(expectedExceptions = ClassCastException.class)
  public void execWrongValueType() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch();
    DataBag bag = bagFactory.newDefaultBag();
    bag.add(tupleFactory.newTuple("a"));
    func.exec(tupleFactory.newTuple(bag));
  }

  @Test
  public void execNormalCase() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch();
    DataBag bag = bagFactory.newDefaultBag();
    bag.add(tupleFactory.newTuple(1.0));
    Tuple resultTuple = func.exec(tupleFactory.newTuple(bag));
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertFalse(sketch.isEmpty());
    Assert.assertEquals(sketch.getN(), 1);
  }

  @Test
  public void accumulator() throws Exception {
    Accumulator<Tuple> func = new DataToDoublesSketch();

    // no input yet
    Tuple resultTuple = func.getValue();
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());

    // null input tuple
    func.accumulate(null);
    resultTuple = func.getValue();
    sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());

    // empty input tuple
    func.accumulate(tupleFactory.newTuple());
    resultTuple = func.getValue();
    sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());

    // empty bag
    func.accumulate(tupleFactory.newTuple(bagFactory.newDefaultBag()));
    resultTuple = func.getValue();
    sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());

    // normal case
    DataBag bag = bagFactory.newDefaultBag();
    bag.add(tupleFactory.newTuple(1.0));
    func.accumulate(tupleFactory.newTuple(bag));
    func.accumulate(tupleFactory.newTuple(bag));
    resultTuple = func.getValue();
    sketch = getSketch(resultTuple);
    Assert.assertFalse(sketch.isEmpty());
    Assert.assertEquals(sketch.getN(), 2);

    // cleanup
    func.cleanup();
    resultTuple = func.getValue();
    sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());
  }

  @Test
  public void algebraicInitial() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch.Initial();
    DataBag bag = bagFactory.newDefaultBag();
    bag.add(tupleFactory.newTuple());
    Tuple resultTuple = func.exec(tupleFactory.newTuple(bag));
    Assert.assertNotNull(resultTuple);
    Assert.assertEquals(resultTuple.size(), 1);
    Assert.assertTrue(resultTuple.get(0) instanceof DataBag);
    Assert.assertEquals(((DataBag) resultTuple.get(0)).size(), 1);
  }

  @Test
  public void algebraicIntermediateFinalNullInputTuple() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch.IntermediateFinal();
    Tuple resultTuple = func.exec(null);
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());
  }

  @Test
  public void algebraicIntermediateFinalEmptyInputTuple() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch.IntermediateFinal();
    Tuple resultTuple = func.exec(tupleFactory.newTuple());
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertTrue(sketch.isEmpty());
  }

  @Test
  public void algebraicIntermediateFinalNormalCase() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch.IntermediateFinal();
    DataBag bag = bagFactory.newDefaultBag();

    { // this is to simulate an output from Initial
      DataBag innerBag = bagFactory.newDefaultBag();
      innerBag.add(tupleFactory.newTuple(1.0));
      bag.add(tupleFactory.newTuple(innerBag));
    }

    { // this is to simulate an output from a prior call of IntermediateFinal
      DoublesSketch qs = DoublesSketch.builder().build();
      qs.update(2.0);
      bag.add(tupleFactory.newTuple(new DataByteArray(qs.toByteArray())));
    }

    Tuple resultTuple = func.exec(tupleFactory.newTuple(bag));
    DoublesSketch sketch = getSketch(resultTuple);
    Assert.assertFalse(sketch.isEmpty());
    Assert.assertEquals(sketch.getN(), 2);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void algebraicIntermediateFinalWrongType() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch.IntermediateFinal();
    DataBag bag = bagFactory.newDefaultBag();

    // this bag must have tuples with either bags or data byte arrays
    bag.add(tupleFactory.newTuple(1.0));
    func.exec(tupleFactory.newTuple(bag));
  }

  @Test
  public void schema() throws Exception {
    EvalFunc<Tuple> func = new DataToDoublesSketch();
    Schema schema = func.outputSchema(new Schema());
    Assert.assertNotNull(schema);
    Assert.assertEquals(schema.size(), 1);
    Assert.assertEquals(schema.getField(0).type, DataType.TUPLE);
    Assert.assertEquals(schema.getField(0).schema.size(), 1);
    Assert.assertEquals(schema.getField(0).schema.getField(0).type, DataType.BYTEARRAY);
  }

  // end of tests

  private static DoublesSketch getSketch(Tuple tuple) throws Exception {
    Assert.assertNotNull(tuple);
    Assert.assertEquals(tuple.size(), 1);
    DataByteArray bytes = (DataByteArray) tuple.get(0);
    Assert.assertTrue(bytes.size() > 0);
    return DoublesSketch.heapify(new NativeMemory(bytes.get()));
  }
}
