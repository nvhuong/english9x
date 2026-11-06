package com.english9x.domain.billing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SeatPoolTest {
    @Test
    public void assignUntilFull() {
        SeatPool p = new SeatPool(2);
        p.assign("a");
        p.assign("b");
        assertEquals(0, p.available());
        assertThrows(IllegalStateException.class, () -> p.assign("c"));
    }

    @Test
    public void revokeFreesSeat() {
        SeatPool p = new SeatPool(1);
        p.assign("a");
        p.revoke("a");
        assertEquals(1, p.available());
        assertFalse(p.isAssigned("a"));
        p.assign("b");
        assertTrue(p.isAssigned("b"));
    }

    @Test
    public void cannotAssignTwice() {
        SeatPool p = new SeatPool(3);
        p.assign("a");
        assertThrows(IllegalStateException.class, () -> p.assign("a"));
    }

    @Test
    public void cannotRevokeUnassigned() {
        SeatPool p = new SeatPool(3);
        assertThrows(IllegalStateException.class, () -> p.revoke("a"));
    }
}
