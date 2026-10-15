package com.fingerrehab.algorithm;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DifficultyRecommenderTest {
  private List<DifficultyRecommender.Session> sessions(double score, int difficulty, int count) {
    List<DifficultyRecommender.Session> list = new ArrayList<>();
    for (int i = 0; i < count; i++)
      list.add(new DifficultyRecommender.Session("trace", difficulty, score, true, true, i));
    return list;
  }

  @Test
  public void fewerThanFiveHasNoAutomaticConclusion() {
    assertFalse(DifficultyRecommender.recommend(null, "trace", 1).sufficientData);
    DifficultyRecommender.Recommendation r =
        DifficultyRecommender.recommend(sessions(99, 1, 4), "trace", 1);
    assertFalse(r.sufficientData);
    assertNull(r.averageScore);
    assertEquals(1, r.difficulty);
  }

  @Test
  public void thresholdsAndDifficultyBoundsAreExact() {
    assertEquals(2, DifficultyRecommender.recommend(sessions(85, 1, 5), "trace", 1).difficulty);
    assertEquals(0, DifficultyRecommender.recommend(sessions(59.9, 1, 5), "trace", 1).difficulty);
    assertEquals(1, DifficultyRecommender.recommend(sessions(60, 1, 5), "trace", 1).difficulty);
    assertEquals(1, DifficultyRecommender.recommend(sessions(84.9, 1, 5), "trace", 1).difficulty);
    assertEquals(2, DifficultyRecommender.recommend(sessions(100, 2, 5), "trace", 2).difficulty);
    assertEquals(0, DifficultyRecommender.recommend(sessions(1, 0, 5), "trace", 0).difficulty);
  }

  @Test
  public void ignoresOtherTypesLevelsInvalidIncompleteAndNonfiniteScores() {
    List<DifficultyRecommender.Session> list = sessions(90, 1, 4);
    list.add(new DifficultyRecommender.Session("reaction", 1, 100, true, true, 100));
    list.add(new DifficultyRecommender.Session("trace", 2, 100, true, true, 100));
    list.add(new DifficultyRecommender.Session("trace", 1, 100, false, true, 100));
    list.add(new DifficultyRecommender.Session("trace", 1, 100, true, false, 100));
    list.add(new DifficultyRecommender.Session("trace", 1, Double.NaN, true, true, 100));
    assertFalse(DifficultyRecommender.recommend(list, "trace", 1).sufficientData);
  }

  @Test
  public void usesOnlyNewestFiveAfterSortingWithoutMutatingInput() {
    List<DifficultyRecommender.Session> list = sessions(20, 1, 8);
    for (int i = 8; i < 13; i++)
      list.add(0, new DifficultyRecommender.Session("trace", 1, 90, true, true, i));
    DifficultyRecommender.Recommendation r = DifficultyRecommender.recommend(list, "trace", 1);
    assertEquals(90, r.averageScore, 0);
    assertEquals(2, r.difficulty);
    assertEquals(12, list.get(0).endedAt);
  }
}
