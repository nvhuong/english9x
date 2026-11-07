package com.english9x.catalog.api;

/** No published score table (readable in the current context) for the exam and skill. Never answered with a made-up conversion. */
public class ScoreTableNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ScoreTableNotFoundException(String examCode, String skill) {
        super("no published score table for " + examCode + " / " + skill);
    }
}
