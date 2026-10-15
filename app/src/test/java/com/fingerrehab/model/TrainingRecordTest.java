package com.fingerrehab.model;

import static org.junit.Assert.*;

import org.junit.Test;

public class TrainingRecordTest {
  @Test
  public void effectiveCompletionRequiresFiniteBoundedScoreAndValidCompletedState() {
    TrainingRecord record = new TrainingRecord();
    record.valid = true;
    record.status = "completed";
    for (double score : new double[] {0, 50, 100}) {
      record.score = score;
      assertTrue(record.isEffectiveCompletion());
    }
    for (double score :
        new double[] {-1, 100.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
      record.score = score;
      assertFalse(record.isEffectiveCompletion());
    }
    record.score = 50;
    for (String status : new String[] {null, "incomplete", "aborted"}) {
      record.status = status;
      assertFalse(record.isEffectiveCompletion());
    }
    record.status = "completed";
    record.valid = false;
    assertFalse(record.isEffectiveCompletion());
  }
}
