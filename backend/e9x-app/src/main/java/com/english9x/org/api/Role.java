package com.english9x.org.api;

/** Roles of spec §4. The database stores the enum name. */
public enum Role {
    LEARNER,
    AUTHOR,
    REVIEWER,
    TEACHER,
    ORG_ADMIN,
    SYS_ADMIN;

    /** Roles that always apply to every exam (their grant has {@code exam = NULL}). Everything else is per exam. */
    public boolean coversEveryExam() {
        return this == ORG_ADMIN || this == SYS_ADMIN || this == LEARNER;
    }

    /** Roles an ENTERPRISE organisation can never have (spec §4). */
    public boolean forbiddenInEnterprise() {
        return this == AUTHOR || this == REVIEWER || this == TEACHER;
    }
}
