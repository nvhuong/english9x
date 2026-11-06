package com.english9x.domain.scoring;

public interface ItemScorer {
    ItemResult score(AnswerKey key, Answer answer);
}
