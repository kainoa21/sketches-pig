/*
 * Copyright 2015, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.pig.tuple;

import static com.yahoo.sketches.Util.DEFAULT_NOMINAL_ENTRIES;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.pig.EvalFunc;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.Tuple;

import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.tuple.ArrayOfDoublesSetOperationBuilder;
import com.yahoo.sketches.tuple.ArrayOfDoublesSketches;
import com.yahoo.sketches.tuple.ArrayOfDoublesUnion;

/**
 * This is to calculate the intermediate pass (combiner) or the final pass
 * (reducer) of an Algebraic sketch operation. This may be called multiple times
 * (from the mapper and from the reducer). It will receive a bag of values
 * returned by either the Intermediate or the Initial stages, so
 * it needs to be able to differentiate between and interpret both types.
 */
abstract class UnionArrayOfDoublesSketchAlgebraicIntermediateFinal extends EvalFunc<Tuple> {
  private final int sketchSize_;
  private final int numValues_;
  private boolean isFirstCall_ = true;

  UnionArrayOfDoublesSketchAlgebraicIntermediateFinal() {
    this(DEFAULT_NOMINAL_ENTRIES, 1);
  }

  UnionArrayOfDoublesSketchAlgebraicIntermediateFinal(final int numValues) {
    this(DEFAULT_NOMINAL_ENTRIES, numValues);
  }

  UnionArrayOfDoublesSketchAlgebraicIntermediateFinal(final int sketchSize, final int numValues) {
    super();
    sketchSize_ = sketchSize;
    numValues_ = numValues;
  }

  @Override
  public Tuple exec(final Tuple inputTuple) throws IOException {
    if (isFirstCall_) {
      // this is to see in the log which way was used by Pig
      Logger.getLogger(getClass()).info("algebraic is used");
      isFirstCall_ = false;
    }
    final ArrayOfDoublesUnion union = 
        new ArrayOfDoublesSetOperationBuilder().setNominalEntries(sketchSize_)
          .setNumberOfValues(numValues_).buildUnion();

    final DataBag bag = (DataBag) inputTuple.get(0);
    if (bag == null) throw new IllegalArgumentException("InputTuple.Field0: Bag may not be null");

    for (final Tuple dataTuple: bag) {
      final Object item = dataTuple.get(0);
      if (item instanceof DataBag) {
        // this is from a prior call to the initial function, so there is a nested bag.
        for (final Tuple innerTuple: (DataBag) item) {
          final DataByteArray dba = (DataByteArray) innerTuple.get(0);
          union.update(ArrayOfDoublesSketches.wrapSketch(new NativeMemory(dba.get())));
        }
      } else if (item instanceof DataByteArray) {
        // This is a sketch from a call to the Intermediate function 
        // Add it to the current union
        final DataByteArray dba = (DataByteArray) item;
        union.update(ArrayOfDoublesSketches.wrapSketch(new NativeMemory(dba.get())));
      } else {
        // we should never get here.
        throw new IllegalArgumentException("InputTuple.Field0: Bag contains unrecognized types: " 
            + item.getClass().getName());
      }
    }
    return Util.tupleFactory.newTuple(new DataByteArray(union.getResult().toByteArray()));
  }
}
