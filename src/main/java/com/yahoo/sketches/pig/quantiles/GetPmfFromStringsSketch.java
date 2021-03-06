/*
 * Copyright 2016, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.pig.quantiles;

import java.io.IOException;
import java.util.Comparator;

import org.apache.pig.EvalFunc;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.Tuple;

import com.yahoo.memory.NativeMemory;
import com.yahoo.sketches.ArrayOfStringsSerDe;
import com.yahoo.sketches.quantiles.ItemsSketch;

/**
 * This UDF is to get an approximation to the Probability Mass Function (PMF) of the input stream 
 * given a sketch and a set of split points - an array of <i>m</i> unique, monotonically increasing
 * values that divide the domain into <i>m+1</i> consecutive disjoint intervals.
 * The function returns an array of m+1 doubles each of which is an approximation to the fraction
 * of the input stream values that fell into one of those intervals. Intervals are inclusive of
 * the left split point and exclusive of the right split point.
 */
public class GetPmfFromStringsSketch extends EvalFunc<Tuple> {

  @Override
  public Tuple exec(final Tuple input) throws IOException {
    if (input.size() < 2) throw new IllegalArgumentException("expected two or more inputs: sketch and list of split points");

    if (!(input.get(0) instanceof DataByteArray)) {
      throw new IllegalArgumentException("expected a DataByteArray as a sketch, got " + input.get(0).getClass().getSimpleName());
    }
    final DataByteArray dba = (DataByteArray) input.get(0);
    final ItemsSketch<String> sketch = 
        ItemsSketch.getInstance(new NativeMemory(dba.get()), Comparator.naturalOrder(), 
            new ArrayOfStringsSerDe());

    String[] splitPoints = new String[input.size() - 1];
    for (int i = 1; i < input.size(); i++) {
      if (!(input.get(i) instanceof String)) {
        throw new IllegalArgumentException("expected a string value as a split point, got " 
      + input.get(i).getClass().getSimpleName());
      }
      splitPoints[i - 1] = (String) input.get(i);
    }
    return Util.doubleArrayToTuple(sketch.getPMF(splitPoints));
  }

}
