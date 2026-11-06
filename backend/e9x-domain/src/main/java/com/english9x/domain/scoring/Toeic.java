package com.english9x.domain.scoring;

public final class Toeic {

    private Toeic() {
    }

    /** Total of Listening and Reading section scores, each 5..495 in steps of 5. */
    public static int total(int listening, int reading) {
        requireSection(listening, "listening");
        requireSection(reading, "reading");
        return listening + reading;
    }

    private static void requireSection(int score, String name) {
        if (score < 5 || score > 495 || score % 5 != 0) {
            throw new IllegalArgumentException(name + " must be in [5, 495] and a multiple of 5: " + score);
        }
    }
}
