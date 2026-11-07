-- P1.8: Spring Modulith event publication registry (spec §6, at-least-once delivery of module events).
--
-- Schema copied from spring-modulith-events-jdbc 1.4.14 (schema-postgresql.sql). Flyway owns it: schema initialisation by the
-- library is off (application.yml). A publication row is written in the PUBLISHER's transaction, one per listener of the event,
-- and deleted when that listener completed (spring.modulith.events.completion-mode=delete, so nothing lingers). A listener that
-- failed or was interrupted leaves its row; it is resubmitted at start-up and by NotificationEventRedelivery.
--
-- Infrastructure, not tenant data: it holds no owner_org_id. RLS is on (every table has it, see db-tests/13) with one permissive policy
-- for the request role, because the library reads and writes it as that role. The serialized event holds ids (and, for UserRegistered,
-- an address) for as long as a listener has not completed it.
CREATE TABLE event_publication
(
  id               UUID NOT NULL,
  listener_id      TEXT NOT NULL,
  event_type       TEXT NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date  TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

ALTER TABLE event_publication ENABLE ROW LEVEL SECURITY;
CREATE POLICY event_publication_app ON event_publication FOR ALL TO e9x_app USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON event_publication TO e9x_app;
