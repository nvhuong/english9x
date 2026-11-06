package com.english9x.domain.scoring;

/** How a learner responds to an item; decides whether it can be scored automatically. */
public enum InteractionKind {
    MCQ_SINGLE,
    MCQ_MULTI,
    GAP_TYPED,
    GAP_SELECT,
    MATCHING,
    TRUE_FALSE_NG,
    YES_NO_NG,
    LABEL_DIAGRAM,
    SHORT_ANSWER,
    ORDERING,
    ESSAY,
    SPEAKING_RESPONSE;

    public boolean autoScored() {
        return this != ESSAY && this != SPEAKING_RESPONSE;
    }
}
