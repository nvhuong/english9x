-- P2.2 media (feature F12): the record of every uploaded audio, image and video file. The bytes live in MinIO (private bucket,
-- object key = the row's id, never a name chosen by the client); this table is what says whose they are, what they are and how long.
--
-- The kind and the mime type are what the SERVER found in the bytes (MediaSniffer), the duration what ffprobe read from the stored
-- file: never the client's file name, Content-Type or claim.
--
-- Access, like the content that uses the file (owner NULL = English9x):
--   * read by the AUTHOR or REVIEWER of the exam, in exactly the owner's context (a reviewer has to hear the audio under review).
--     Nobody else: not a learner, not a teacher, not the author of another exam, not the same person in another context.
--     P3.1 (entitled learners, needs lessons and course versions that do not exist yet) opens its own path with a SECURITY DEFINER
--     function; until then every learner is refused.
--   * inserted by the AUTHOR of the exam in the owner's context, as themselves. Never updated, never deleted: published content points
--     at the file. (Cleaning up orphans is a later, privileged job.)
CREATE TABLE media_assets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  kind text NOT NULL CHECK (kind IN ('AUDIO', 'IMAGE', 'VIDEO')),
  mime_type text NOT NULL CHECK (mime_type IN (
    'audio/mpeg', 'audio/mp4', 'audio/webm', 'audio/ogg',
    'image/png', 'image/jpeg', 'image/webp',
    'video/mp4', 'video/webm')),
  size_bytes bigint NOT NULL CHECK (size_bytes > 0),
  -- read from the stored file by ffprobe; images have none
  duration_ms bigint,
  -- for display only; the object key is the id
  original_filename text NOT NULL CHECK (length(original_filename) BETWEEN 1 AND 255),
  uploaded_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT media_assets_kind_mime_ck CHECK (mime_type LIKE lower(kind) || '/%'),
  CONSTRAINT media_assets_duration_ck CHECK ((kind = 'IMAGE' AND duration_ms IS NULL) OR (kind <> 'IMAGE' AND coalesce(duration_ms > 0, false)))
);
CREATE INDEX media_assets_owner_exam_idx ON media_assets (owner_org_id, exam_code, created_at DESC);
ALTER TABLE media_assets ENABLE ROW LEVEL SECURITY;

CREATE POLICY media_assets_select ON media_assets FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY media_assets_insert ON media_assets FOR INSERT TO e9x_app
  WITH CHECK (e9x_workflow_in_context(owner_org_id)
              AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND uploaded_by = e9x_current_user());

GRANT SELECT ON media_assets TO e9x_app;
GRANT INSERT (id, owner_org_id, exam_code, kind, mime_type, size_bytes, duration_ms, original_filename, uploaded_by) ON media_assets TO e9x_app;
