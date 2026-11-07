package com.english9x.notification.api;

/** The delivery channels of spec §13. {@link #IN_APP} is always on; the others are chosen per event in the preferences. */
public enum Channel {
    IN_APP, EMAIL, TELEGRAM;

    /** Whether the person can switch this channel off. */
    public boolean optional() {
        return this != IN_APP;
    }
}
