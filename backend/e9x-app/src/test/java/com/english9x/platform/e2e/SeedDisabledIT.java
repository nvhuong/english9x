package com.english9x.platform.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Production behaviour (no "e2e" profile): the seed API does not exist. The route is a plain 404 and none of the
 * test-support beans are in the context, so there is nothing to call and nothing holding a table-owner connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeedDisabledIT {

    static final String DB = TestPostgres.createDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment env;

    @Test
    void theE2eProfileIsNotActive() {
        assertFalse(env.matchesProfiles("e2e"));
    }

    @Test
    void seedRoutesAreNotFound() {
        for (String path : new String[] {"/api/test/seed/ping", "/api/test/seed/anything", "/api/test/seed"}) {
            ResponseEntity<String> post = http.postForEntity(path, null, String.class);
            assertEquals(404, post.getStatusCode().value(), path + ": " + post.getBody());
            ResponseEntity<String> get = http.getForEntity(path, String.class);
            assertEquals(404, get.getStatusCode().value(), path + ": " + get.getBody());
        }
    }

    @Test
    void noTestSupportBeansExist() {
        assertEquals(0, context.getBeanNamesForType(SeedController.class).length, "SeedController bean");
        assertEquals(0, context.getBeanNamesForType(SeedDatabase.class).length, "SeedDatabase bean");
        for (String name : context.getBeanDefinitionNames()) {
            assertFalse(
                    context.getType(name) != null
                            && context.getType(name).getName().startsWith("com.english9x.platform.e2e"),
                    "no bean from the e2e package may exist without the profile: " + name);
        }
        assertTrue(context.getBeanNamesForType(javax.sql.DataSource.class).length >= 1);
    }
}
