package com.english9x.content.importer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** A lot of imported drafts is submitted as ONE review bundle (spec 7.1), so max-rows must fit e9x.workflow.max-bundle-size: the application refuses to start otherwise. */
class ImportConfigurationTest {

    private static ImportProperties rows(int n) {
        return new ImportProperties(n, DataSize.ofMegabytes(500));
    }

    @Test
    void maxRowsMustFitTheBundle() {
        assertDoesNotThrow(() -> new ImportConfiguration(rows(2000), 2000));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ImportConfiguration(rows(2001), 2000));
        assertTrue(e.getMessage().contains("e9x.workflow.max-bundle-size"), e.getMessage());
    }

    @Test
    void theKeysHaveTheirBounds() {
        assertThrows(IllegalStateException.class, () -> rows(0));
        assertThrows(IllegalStateException.class, () -> rows(5001));
        assertThrows(IllegalStateException.class, () -> new ImportProperties(10, DataSize.ofBytes(10)));
    }
}
