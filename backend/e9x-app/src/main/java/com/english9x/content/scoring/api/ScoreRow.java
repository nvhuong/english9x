package com.english9x.content.scoring.api;

/** One line of a conversion table: raw correct answers to the scaled score (a band or a TOEIC section score). */
public record ScoreRow(int raw, double scaled) {}
