package com.english9x.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.workflow.internal.WorkflowProperties;
import org.junit.jupiter.api.Test;

/** The configured bundle limit must fit the database's own bound (review_bundles.member_count 1..5000). */
class WorkflowPropertiesTest {

    @Test
    void acceptsTheWholeRangeOfTheDatabaseCheck() {
        assertDoesNotThrow(() -> new WorkflowProperties(1));
        assertDoesNotThrow(() -> new WorkflowProperties(2000));
        assertDoesNotThrow(() -> new WorkflowProperties(5000));
    }

    @Test
    void refusesAValueTheDatabaseWouldTurnIntoAServerError() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowProperties(0));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowProperties(-1));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowProperties(5001));
    }
}
