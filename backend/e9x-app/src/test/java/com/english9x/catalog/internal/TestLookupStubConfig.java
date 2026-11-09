package com.english9x.catalog.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for the P2.6 lookup (test version to test) in the integration tests of the override keys: there is no {@code test_versions}
 * table before P2.6. The tests that import it drop the TEST-scope refusal in their own throwaway database, the way P2.6's migration will.
 */
@TestConfiguration
public class TestLookupStubConfig {

    private static final Map<UUID, UUID> TEST_OF_VERSION = new ConcurrentHashMap<>();

    /** Declares that {@code testVersionId} is a version of {@code testId}. */
    public static void version(UUID testVersionId, UUID testId) {
        TEST_OF_VERSION.put(testVersionId, testId);
    }

    @Bean
    @Primary
    TestLookup stubbedTestLookup() {
        return new TestLookup() {
            @Override
            Optional<UUID> testOf(UUID testVersionId) {
                return Optional.ofNullable(TEST_OF_VERSION.get(testVersionId));
            }
        };
    }
}
