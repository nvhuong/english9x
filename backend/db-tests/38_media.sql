-- P2.2 media_assets (feature F12). A media row belongs to an owner (NULL = English9x) and an exam, like the content that uses it.
--  * Read by the AUTHOR / REVIEWER of THAT exam in the owner's context only: not by a learner, not by a teacher, not by the author of
--    another exam, not in another organisation's context. (P3.1 opens the learner path through a SECURITY DEFINER function of its own.)
--  * Written (INSERT only) by the AUTHOR of the exam in the owner's context, as themselves. Immutable afterwards: no UPDATE, no DELETE.
--  * The columns say what the file is: kind and mime agree, an image has no duration, audio and video have one.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('d1000000-0000-0000-0000-000000000001','author-a-ielts@x.test'),
 ('d1000000-0000-0000-0000-000000000002','reviewer-a-ielts@x.test'),
 ('d1000000-0000-0000-0000-000000000003','learner-a@x.test'),
 ('d1000000-0000-0000-0000-000000000004','author-b-ielts@x.test'),
 ('d1000000-0000-0000-0000-000000000005','author-e9-ielts@x.test'),
 ('d1000000-0000-0000-0000-000000000006','teacher-a-ielts@x.test'),
 ('d1000000-0000-0000-0000-000000000007','author-a-toeic@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','d1000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d1000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d1000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d1000000-0000-0000-0000-000000000006'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d1000000-0000-0000-0000-000000000007'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','d1000000-0000-0000-0000-000000000004');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('d1000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('d1000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('d1000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('d1000000-0000-0000-0000-000000000004','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AUTHOR'),
 ('d1000000-0000-0000-0000-000000000005',NULL,'IELTS','AUTHOR'),
 ('d1000000-0000-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','TEACHER'),
 ('d1000000-0000-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','AUTHOR');

-- fixtures as the owner of the tables: one file of org A, one of org B, one of English9x, all IELTS
INSERT INTO media_assets(id,owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES
 ('e1000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUDIO','audio/mpeg',1000,1500,'a.mp3','d1000000-0000-0000-0000-000000000001'),
 ('e1000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AUDIO','audio/mpeg',1000,1500,'b.mp3','d1000000-0000-0000-0000-000000000004'),
 ('e1000000-0000-0000-0000-0000000000e9',NULL,'IELTS','IMAGE','image/png',500,NULL,'e9.png','d1000000-0000-0000-0000-000000000005');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app, e9x_grader;

-- the table itself
DO $$
BEGIN
  ASSERT (SELECT relrowsecurity FROM pg_class WHERE relname = 'media_assets'), 'media_assets has row level security';
END $$;

-- the columns say what the file is (as the owner: the constraints, not the policies)
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''AUDIO'',''image/png'',10,100,''x'',''d1000000-0000-0000-0000-000000000005'')') = 'err:23514', 'kind and mime agree';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''IMAGE'',''image/png'',10,100,''x'',''d1000000-0000-0000-0000-000000000005'')') = 'err:23514', 'an image has no duration';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''AUDIO'',''audio/mpeg'',10,NULL,''x'',''d1000000-0000-0000-0000-000000000005'')') = 'err:23514', 'audio has a duration';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''AUDIO'',''audio/mpeg'',10,0,''x'',''d1000000-0000-0000-0000-000000000005'')') = 'err:23514', 'a duration of zero is no duration';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''VIDEO'',''video/x-flv'',10,100,''x'',''d1000000-0000-0000-0000-000000000005'')') = 'err:23514', 'only the allowed types';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''IMAGE'',''image/png'',0,NULL,''x'',''d1000000-0000-0000-0000-000000000005'')') = 'err:23514', 'an empty file is no file';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''NOPE'',''IMAGE'',''image/png'',1,NULL,''x'',''d1000000-0000-0000-0000-000000000005'')') = 'err:23503', 'the exam exists';
END $$;

-- reading: the AUTHOR and REVIEWER of the exam in the owner's context, nobody else
SET ROLE e9x_app;
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM media_assets) = ARRAY['e1000000-0000-0000-0000-00000000000a'::uuid], 'the author of org A sees org A''s file and nothing else';
END $$;
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM media_assets) = 1, 'the reviewer of the exam sees it (a reviewer must hear the audio under review)';
END $$;
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM media_assets) = 0, 'a learner of the organisation sees none (P3.1 opens the entitlement path)';
END $$;
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000006',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM media_assets) = 0, 'a teacher does not either';
END $$;
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000007',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM media_assets) = 0, 'the author of TOEIC is no author of an IELTS file';
END $$;
-- org B's author, in org B's context
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000004',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM media_assets) = ARRAY['e1000000-0000-0000-0000-00000000000b'::uuid], 'org B sees only its own';
END $$;
-- English9x's author: in the English9x context only, with the staff flag the session sets from the database
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000005',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM media_assets) = ARRAY['e1000000-0000-0000-0000-0000000000e9'::uuid], 'English9x''s author sees English9x''s file';
END $$;
-- the same person in an organisation context does not see English9x's file
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM media_assets) = 0, 'English9x media is not read in an organisation''s context';
END $$;

-- writing: INSERT only, as the AUTHOR of the exam, in the owner's context, as themselves
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''AUDIO'',''audio/mpeg'',10,100,''ok.mp3'',''d1000000-0000-0000-0000-000000000001'')') = 'rows:1', 'the author inserts';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''AUDIO'',''audio/mpeg'',10,100,''x.mp3'',''d1000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into another organisation';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''AUDIO'',''audio/mpeg'',10,100,''x.mp3'',''d1000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into English9x from an organisation context';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''AUDIO'',''audio/mpeg'',10,100,''x.mp3'',''d1000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not for an exam the person is no author of';
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''AUDIO'',''audio/mpeg'',10,100,''x.mp3'',''d1000000-0000-0000-0000-000000000004'')') = 'err:42501', 'not as somebody else';
  ASSERT public.t_attempt('UPDATE media_assets SET duration_ms = 1') = 'err:42501', 'a file is immutable: no UPDATE';
  ASSERT public.t_attempt('DELETE FROM media_assets') = 'err:42501', 'and no DELETE';
END $$;
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''AUDIO'',''audio/mpeg'',10,100,''x.mp3'',''d1000000-0000-0000-0000-000000000002'')') = 'err:42501', 'a reviewer reads, never uploads';
END $$;
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IMAGE'',''image/png'',10,NULL,''x.png'',''d1000000-0000-0000-0000-000000000003'')') = 'err:42501', 'a learner never uploads';
END $$;
-- English9x's author inserts in the English9x context
SELECT set_config('app.user_id','d1000000-0000-0000-0000-000000000005',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO media_assets(owner_org_id,exam_code,kind,mime_type,size_bytes,duration_ms,original_filename,uploaded_by) VALUES (NULL,''IELTS'',''IMAGE'',''image/webp'',10,NULL,''e.webp'',''d1000000-0000-0000-0000-000000000005'')') = 'rows:1', 'English9x''s author inserts English9x media';
END $$;
-- the grader has no business with media
RESET ROLE;
DO $$
BEGIN
  ASSERT NOT has_table_privilege('e9x_grader', 'media_assets', 'SELECT'), 'the grader does not read media rows';
  ASSERT NOT has_table_privilege('e9x_worker', 'media_assets', 'SELECT'), 'nor does the worker';
END $$;
