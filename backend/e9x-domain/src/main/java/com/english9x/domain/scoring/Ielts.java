package com.english9x.domain.scoring;

public final class Ielts {

    private Ielts() {
    }

    /** Average of four bands rounded to the nearest half band (.25 up to .5, .75 up to next whole). */
    public static double overall(double l, double r, double w, double s) {
        requireBand(l);
        requireBand(r);
        requireBand(w);
        requireBand(s);
        double avg = (l + r + w + s) / 4; // exact: sum is a multiple of 0.5
        double whole = Math.floor(avg);
        double frac = avg - whole;
        if (frac < 0.25) {
            return whole;
        }
        return frac < 0.75 ? whole + 0.5 : whole + 1;
    }

    /** Task 2 counts double: (t1 + 2*t2) / 3, rounded down to a multiple of 0.5. */
    public static double writingBand(double task1, double task2) {
        requireBand(task1);
        requireBand(task2);
        int halves = ((int) (task1 * 2) + 2 * (int) (task2 * 2)) / 3;
        return halves / 2.0;
    }

    public static void requireBand(double b) {
        if (!(b >= 0 && b <= 9) || b * 2 != Math.rint(b * 2)) {
            throw new IllegalArgumentException("invalid IELTS band: " + b);
        }
    }
}
