package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.english9x.support.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** An outstanding publication that can no longer be read back must not keep the application from starting. */
@SpringBootTest(properties = {"e9x.events.redelivery-after=1h"})
class EventRedeliveryStartupIT {

    private static final String DB = TestPostgres.createDatabase();

    static {
        Flyway.configure().dataSource(TestPostgres.owner(DB)).locations("classpath:db/migration").load().migrate();
        new JdbcTemplate(TestPostgres.superuser(DB)).update("insert into event_publication(id,listener_id,event_type,serialized_event,publication_date) "
                + "values (gen_random_uuid(), 'it-bad-listener', 'java.lang.ProcessBuilder', 'not json', now() - interval '1 day')");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.connect(registry, DB);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void theContextStartsWithABadPublicationPresent() {
        assertEquals(1, jdbc.queryForObject("select count(*) from event_publication where listener_id = 'it-bad-listener'", Integer.class));
    }
}
