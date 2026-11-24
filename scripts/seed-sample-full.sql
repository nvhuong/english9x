-- ==============================================================================
-- English9x: Dữ liệu mẫu toàn diện (Sample Data) cho hệ thống
-- Bao gồm: Strategy Lessons, Questions, Question Groups, Tests, Score Tables,
--          Courses (Chapters, Lessons, Chapter Tests, Mock Tests),
--          Lịch sử thi mẫu (Exam Attempts) & Thống kê điểm yếu (Weakness Radar),
--          Bảng giá (B2C Plans) và Đơn hàng mẫu.
-- ==============================================================================

DO $$
DECLARE
  v_admin_id uuid;
  v_author_id uuid;
  v_learner_id uuid;

  -- Lessons
  v_l1 uuid; v_l1_v uuid;
  v_l2 uuid; v_l2_v uuid;
  v_l3 uuid; v_l3_v uuid;
  v_l4 uuid; v_l4_v uuid;
  v_l5 uuid; v_l5_v uuid;
  v_l6 uuid; v_l6_v uuid;
  v_l7 uuid; v_l7_v uuid;
  v_l8 uuid; v_l8_v uuid;

  -- Questions & Groups
  v_g1 uuid; v_g1_v uuid;
  v_q1 uuid; v_q1_v uuid;
  v_q2 uuid; v_q2_v uuid;
  v_q3 uuid; v_q3_v uuid;
  v_q4 uuid; v_q4_v uuid;

  -- Test & Test Version
  v_test_id uuid;
  v_test_ver_id uuid;
  v_table_id uuid;
  v_table_ver_id uuid;

  -- Courses
  v_c1_id uuid; v_c1_ver_id uuid;
  v_c2_id uuid; v_c2_ver_id uuid;

  -- Attempt
  v_attempt_id uuid;
  v_plan_id uuid;
BEGIN
  -- 1. Xác định User IDs
  SELECT id INTO v_admin_id FROM users WHERE email = 'admin@english9x.vn';
  SELECT id INTO v_author_id FROM users WHERE email = 'author@english9x.vn';
  SELECT id INTO v_learner_id FROM users WHERE email = 'hocvien@english9x.vn';

  IF v_author_id IS NULL THEN v_author_id := v_admin_id; END IF;
  IF v_admin_id IS NULL THEN
    SELECT id INTO v_admin_id FROM users ORDER BY created_at LIMIT 1;
    v_author_id := v_admin_id;
  END IF;

  IF v_admin_id IS NULL THEN
    RAISE NOTICE '⚠️ Chưa có tài khoản người dùng trong bảng users. Hãy chạy seed-sample-data.mjs trước!';
    RETURN;
  END IF;

  -- ==============================================================================
  -- 2. BÀI HỌC PHƯƠNG PHÁP (Strategy Lessons)
  -- ==============================================================================
  -- Lesson 1: TOEIC P1
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Chiến thuật phân tích tranh miêu tả hành động người') THEN
    v_l1 := gen_random_uuid(); v_l1_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l1, 'TOEIC', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l1_v, v_l1, 'TOEIC', 1, 'PUBLISHED', 'Chiến thuật phân tích tranh miêu tả hành động người',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Phương pháp quan sát tổng quan 3 giây'),
        jsonb_build_object('type', 'paragraph', 'text', 'Khi nhìn tranh có người, lập tức xác định: Chủ thể (Ai?) - Hành động (Đang làm gì?) - Bối cảnh (Ở đâu?). Chú ý các động từ thể hiện hành động tiếp diễn (be + V-ing) như holding, looking at, typing on.')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l1_v, 'TOEIC_P1_PHOTO', 'TOEIC');
  ELSE
    SELECT id INTO v_l1_v FROM strategy_lesson_versions WHERE title = 'Chiến thuật phân tích tranh miêu tả hành động người' LIMIT 1;
  END IF;

  -- Lesson 2: TOEIC P1
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Bẫy tranh miêu tả đồ vật và không gian tĩnh') THEN
    v_l2 := gen_random_uuid(); v_l2_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l2, 'TOEIC', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l2_v, v_l2, 'TOEIC', 1, 'PUBLISHED', 'Bẫy tranh miêu tả đồ vật và không gian tĩnh',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Bẫy bị động Being + PII'),
        jsonb_build_object('type', 'paragraph', 'text', 'Nếu trong tranh KHÔNG có người, loại ngay các đáp án có thì Hiện tại tiếp diễn bị động (is/are being + V-ed) vì cấu trúc này diễn tả hành động đang được thực hiện bởi người.')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l2_v, 'TOEIC_P1_PHOTO', 'TOEIC');
  ELSE
    SELECT id INTO v_l2_v FROM strategy_lesson_versions WHERE title = 'Bẫy tranh miêu tả đồ vật và không gian tĩnh' LIMIT 1;
  END IF;

  -- Lesson 3: TOEIC P5
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Mẹo nhận diện Từ loại nhanh trong 5 giây') THEN
    v_l3 := gen_random_uuid(); v_l3_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l3, 'TOEIC', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l3_v, v_l3, 'TOEIC', 1, 'PUBLISHED', 'Mẹo nhận diện Từ loại nhanh trong 5 giây',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Vị trí của Danh từ và Tính từ'),
        jsonb_build_object('type', 'paragraph', 'text', 'Đứng trước danh từ thường là tính từ (Adj + N). Đứng sau mạo từ a/an/the hoặc tính từ sở hữu (our, their, my) bắt buộc là danh từ.')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l3_v, 'TOEIC_P5_INCOMPLETE_SENTENCE', 'TOEIC');
  ELSE
    SELECT id INTO v_l3_v FROM strategy_lesson_versions WHERE title = 'Mẹo nhận diện Từ loại nhanh trong 5 giây' LIMIT 1;
  END IF;

  -- Lesson 4: TOEIC P5
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Phân biệt By vs Until & Giới từ chỉ mốc thời gian') THEN
    v_l4 := gen_random_uuid(); v_l4_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l4, 'TOEIC', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l4_v, v_l4, 'TOEIC', 1, 'PUBLISHED', 'Phân biệt By vs Until & Giới từ chỉ mốc thời gian',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Quy tắc By và Until'),
        jsonb_build_object('type', 'paragraph', 'text', 'By diễn tả hành động hoàn tất TRƯỚC một hạn chót (submit report by 5 PM). Until diễn tả trạng thái kéo dài LIÊN TỤC cho tới mốc đó (the store is open until 9 PM).')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l4_v, 'TOEIC_P5_INCOMPLETE_SENTENCE', 'TOEIC');
  ELSE
    SELECT id INTO v_l4_v FROM strategy_lesson_versions WHERE title = 'Phân biệt By vs Until & Giới từ chỉ mốc thời gian' LIMIT 1;
  END IF;

  -- Lesson 5: TOEIC P7
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Kỹ thuật nối thông tin đoạn kép và đoạn ba Part 7') THEN
    v_l5 := gen_random_uuid(); v_l5_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l5, 'TOEIC', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l5_v, v_l5, 'TOEIC', 1, 'PUBLISHED', 'Kỹ thuật nối thông tin đoạn kép và đoạn ba Part 7',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Cross-reference Linking Strategy'),
        jsonb_build_object('type', 'paragraph', 'text', 'Câu hỏi suy luận liên đoạn văn luôn cần ghép 2 mảnh ghép: Đọc câu hỏi -> tìm từ khóa ở đoạn 1 -> đối chiếu bảng thông tin/hóa đơn ở đoạn 2 để ra đáp án.')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l5_v, 'TOEIC_P7_MULTIPLE_PASSAGES', 'TOEIC');
  ELSE
    SELECT id INTO v_l5_v FROM strategy_lesson_versions WHERE title = 'Kỹ thuật nối thông tin đoạn kép và đoạn ba Part 7' LIMIT 1;
  END IF;

  -- Lesson 6: IELTS W Task 1
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Cấu trúc bài viết Writing Task 1 chuẩn Band 7.0+') THEN
    v_l6 := gen_random_uuid(); v_l6_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l6, 'IELTS', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l6_v, v_l6, 'IELTS', 1, 'PUBLISHED', 'Cấu trúc bài viết Writing Task 1 chuẩn Band 7.0+',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Bố cục 4 đoạn chuẩn'),
        jsonb_build_object('type', 'paragraph', 'text', 'Bố cục gồm: Introduction (Paraphrase đề bài) - Overview (2 đặc điểm nổi bật nhất) - Body 1 (Nhóm số liệu chi tiết) - Body 2 (Nhóm số liệu so sánh).')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l6_v, 'IELTS_W_AC_TASK1', 'IELTS');
  ELSE
    SELECT id INTO v_l6_v FROM strategy_lesson_versions WHERE title = 'Cấu trúc bài viết Writing Task 1 chuẩn Band 7.0+' LIMIT 1;
  END IF;

  -- Lesson 7: IELTS W Task 2
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Phát triển đoạn văn Task 2 theo phương pháp PEEL') THEN
    v_l7 := gen_random_uuid(); v_l7_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l7, 'IELTS', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l7_v, v_l7, 'IELTS', 1, 'PUBLISHED', 'Phát triển đoạn văn Task 2 theo phương pháp PEEL',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Phương pháp PEEL'),
        jsonb_build_object('type', 'paragraph', 'text', 'Point (Luận điểm chính) - Explanation (Giải thích nguyên nhân) - Example (Dẫn chứng cụ thể) - Link (Câu chốt liên kết lại đề tài).')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l7_v, 'IELTS_W_TASK2', 'IELTS');
  ELSE
    SELECT id INTO v_l7_v FROM strategy_lesson_versions WHERE title = 'Phát triển đoạn văn Task 2 theo phương pháp PEEL' LIMIT 1;
  END IF;

  -- Lesson 8: IELTS Speaking
  IF NOT EXISTS (SELECT 1 FROM strategy_lesson_versions WHERE title = 'Kỹ thuật mở rộng câu trả lời Speaking Part 2') THEN
    v_l8 := gen_random_uuid(); v_l8_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l8, 'IELTS', v_author_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l8_v, v_l8, 'IELTS', 1, 'PUBLISHED', 'Kỹ thuật mở rộng câu trả lời Speaking Part 2',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Kể chuyện theo trình tự thời gian'),
        jsonb_build_object('type', 'paragraph', 'text', 'Tận dụng 1 phút chuẩn bị ghi lại 4-5 gạch đầu dòng theo quy tắc 5W1H (Who, When, Where, What, Why).')
      ), v_author_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l8_v, 'IELTS_S_PART2', 'IELTS');
  ELSE
    SELECT id INTO v_l8_v FROM strategy_lesson_versions WHERE title = 'Kỹ thuật mở rộng câu trả lời Speaking Part 2' LIMIT 1;
  END IF;

  -- ==============================================================================
  -- 3. NGÂN HÀNG CÂU HỎI & ĐỀ THI MẪU (Questions & Tests)
  -- ==============================================================================
  IF NOT EXISTS (SELECT 1 FROM tests WHERE id IN (SELECT test_id FROM test_versions WHERE title LIKE '%TOEIC Mini-Test%')) THEN
    -- 3.1 Nhóm câu hỏi Part 5
    v_g1 := gen_random_uuid(); v_g1_v := gen_random_uuid();
    INSERT INTO question_groups(id, owner_org_id, exam_code, created_by)
    VALUES (v_g1, NULL, 'TOEIC', v_author_id);

    INSERT INTO question_group_versions(id, group_id, owner_org_id, exam_code, version_no, status, content, solution, created_by)
    VALUES (v_g1_v, v_g1, NULL, 'TOEIC', 1, 'DRAFT',
      jsonb_build_object('title', 'Part 5 Incomplete Sentences', 'instructions', 'Select the best answer to complete each sentence.'),
      '{}'::jsonb, v_author_id);

    -- Question 1
    v_q1 := gen_random_uuid(); v_q1_v := gen_random_uuid();
    INSERT INTO questions(id, owner_org_id, exam_code, type_code, group_id, created_by)
    VALUES (v_q1, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', v_g1, v_author_id);

    INSERT INTO question_versions(id, question_id, owner_org_id, exam_code, type_code, version_no, status, content, answer_key, explanation, points, created_by)
    VALUES (v_q1_v, v_q1, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 1, 'DRAFT',
      jsonb_build_object(
        'stem', 'Customer satisfaction remains our highest _______ for the upcoming fiscal quarter.',
        'options', jsonb_build_array(
          jsonb_build_object('key', 'A', 'text', 'priority'),
          jsonb_build_object('key', 'B', 'text', 'prioritize'),
          jsonb_build_object('key', 'C', 'text', 'prioritized'),
          jsonb_build_object('key', 'D', 'text', 'prioritizing')
        )
      ),
      jsonb_build_object('slots', jsonb_build_array(jsonb_build_array('A'))),
      'Đứng sau tính từ sở hữu "our highest" cần một Danh từ (priority).',
      1, v_author_id);

    -- Question 2
    v_q2 := gen_random_uuid(); v_q2_v := gen_random_uuid();
    INSERT INTO questions(id, owner_org_id, exam_code, type_code, group_id, created_by)
    VALUES (v_q2, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', v_g1, v_author_id);

    INSERT INTO question_versions(id, question_id, owner_org_id, exam_code, type_code, version_no, status, content, answer_key, explanation, points, created_by)
    VALUES (v_q2_v, v_q2, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 1, 'DRAFT',
      jsonb_build_object(
        'stem', 'All department expense reports must be submitted to the accounting office _______ Friday afternoon.',
        'options', jsonb_build_array(
          jsonb_build_object('key', 'A', 'text', 'by'),
          jsonb_build_object('key', 'B', 'text', 'until'),
          jsonb_build_object('key', 'C', 'text', 'during'),
          jsonb_build_object('key', 'D', 'text', 'while')
        )
      ),
      jsonb_build_object('slots', jsonb_build_array(jsonb_build_array('A'))),
      'Giới từ "By" dùng để chỉ hạn chót mà hành động phải hoàn tất trước thời điểm đó.',
      1, v_author_id);

    -- Question 3
    v_q3 := gen_random_uuid(); v_q3_v := gen_random_uuid();
    INSERT INTO questions(id, owner_org_id, exam_code, type_code, group_id, created_by)
    VALUES (v_q3, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', v_g1, v_author_id);

    INSERT INTO question_versions(id, question_id, owner_org_id, exam_code, type_code, version_no, status, content, answer_key, explanation, points, created_by)
    VALUES (v_q3_v, v_q3, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 1, 'DRAFT',
      jsonb_build_object(
        'stem', 'Ms. Tran was promoted to senior director because of her _______ negotiation abilities.',
        'options', jsonb_build_array(
          jsonb_build_object('key', 'A', 'text', 'exceptionally'),
          jsonb_build_object('key', 'B', 'text', 'exceptional'),
          jsonb_build_object('key', 'C', 'text', 'exception'),
          jsonb_build_object('key', 'D', 'text', 'exceptionality')
        )
      ),
      jsonb_build_object('slots', jsonb_build_array(jsonb_build_array('B'))),
      'Đứng trước cụm danh từ "negotiation abilities" cần một Tính từ bổ nghĩa (exceptional).',
      1, v_author_id);

    -- Question 4
    v_q4 := gen_random_uuid(); v_q4_v := gen_random_uuid();
    INSERT INTO questions(id, owner_org_id, exam_code, type_code, group_id, created_by)
    VALUES (v_q4, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', v_g1, v_author_id);

    INSERT INTO question_versions(id, question_id, owner_org_id, exam_code, type_code, version_no, status, content, answer_key, explanation, points, created_by)
    VALUES (v_q4_v, v_q4, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 1, 'DRAFT',
      jsonb_build_object(
        'stem', 'The boardroom members unanimously agreed that the strategic campaign was _______ executed.',
        'options', jsonb_build_array(
          jsonb_build_object('key', 'A', 'text', 'flawless'),
          jsonb_build_object('key', 'B', 'text', 'flawlessly'),
          jsonb_build_object('key', 'C', 'text', 'flaw'),
          jsonb_build_object('key', 'D', 'text', 'flawing')
        )
      ),
      jsonb_build_object('slots', jsonb_build_array(jsonb_build_array('B'))),
      'Đứng trước động từ quá khứ phân từ "executed" cần một Trạng từ bổ nghĩa (flawlessly).',
      1, v_author_id);

    -- Link questions to group
    INSERT INTO question_group_items(group_version_id, position, question_version_id, owner_org_id, exam_code)
    VALUES
      (v_g1_v, 1, v_q1_v, NULL, 'TOEIC'),
      (v_g1_v, 2, v_q2_v, NULL, 'TOEIC'),
      (v_g1_v, 3, v_q3_v, NULL, 'TOEIC'),
      (v_g1_v, 4, v_q4_v, NULL, 'TOEIC');

    -- Publish questions & group
    UPDATE question_versions SET status = 'PUBLISHED' WHERE id IN (v_q1_v, v_q2_v, v_q3_v, v_q4_v);
    UPDATE question_group_versions SET status = 'PUBLISHED' WHERE id = v_g1_v;

    -- 3.2 Tạo Đề thi TOEIC Mini-Test
    v_test_id := gen_random_uuid(); v_test_ver_id := gen_random_uuid();
    INSERT INTO tests(id, owner_org_id, exam_code, variant, created_by)
    VALUES (v_test_id, NULL, 'TOEIC', NULL, v_author_id);

    INSERT INTO test_versions(id, test_id, owner_org_id, exam_code, variant, version_no, status, title, max_resumes, created_by)
    VALUES (v_test_ver_id, v_test_id, NULL, 'TOEIC', NULL, 1, 'DRAFT',
      'TOEIC Mini-Test 2026: Trọng Điểm Part 5', 3, v_author_id);

    -- Part in test (Section P5, Duration 15 mins = 900s)
    INSERT INTO test_parts(test_version_id, position, section_code, duration_sec, owner_org_id, exam_code)
    VALUES (v_test_ver_id, 1, 'P5', 900, NULL, 'TOEIC');

    INSERT INTO test_part_groups(test_version_id, part_position, position, group_version_id, owner_org_id, exam_code)
    VALUES (v_test_ver_id, 1, 1, v_g1_v, NULL, 'TOEIC');

    -- Publish test version
    UPDATE test_versions SET status = 'PUBLISHED' WHERE id = v_test_ver_id;

    -- 3.3 Tạo Bảng quy đổi điểm cho bài test này (Score Table)
    v_table_id := gen_random_uuid(); v_table_ver_id := gen_random_uuid();
    INSERT INTO score_tables(id, owner_org_id, exam_code, skill, scope, test_id)
    VALUES (v_table_id, NULL, 'TOEIC', 'READING', 'TEST', v_test_id);

    INSERT INTO score_table_versions(id, score_table_id, owner_org_id, exam_code, version_no, status, entries)
    VALUES (v_table_ver_id, v_table_id, NULL, 'TOEIC', 1, 'PUBLISHED',
      jsonb_build_array(
        jsonb_build_object('raw', 0, 'scaled', 5),
        jsonb_build_object('raw', 1, 'scaled', 70),
        jsonb_build_object('raw', 2, 'scaled', 150),
        jsonb_build_object('raw', 3, 'scaled', 290),
        jsonb_build_object('raw', 4, 'scaled', 450)
      ));
  ELSE
    SELECT id INTO v_test_ver_id FROM test_versions WHERE title LIKE '%TOEIC Mini-Test%' AND status = 'PUBLISHED' LIMIT 1;
  END IF;

  -- ==============================================================================
  -- 4. KHÓA HỌC & GIÁO TRÌNH (Courses, Chapters, Lessons)
  -- ==============================================================================
  -- 4.1 Khóa học TOE650
  IF NOT EXISTS (SELECT 1 FROM courses WHERE code = 'TOE650') THEN
    v_c1_id := gen_random_uuid(); v_c1_ver_id := gen_random_uuid();
    INSERT INTO courses(id, exam_code, code, title, status, created_by)
    VALUES (v_c1_id, 'TOEIC', 'TOE650', 'TOEIC 450 - 650+: Chiến Thuật Toàn Diện 7 Parts', 'DRAFT', v_admin_id);

    INSERT INTO course_versions(id, course_id, exam_code, version_no, status, title, description, author_display, unlock_rule, created_by)
    VALUES (v_c1_ver_id, v_c1_id, 'TOEIC', 1, 'DRAFT', 'TOEIC 450 - 650+: Chiến Thuật Toàn Diện 7 Parts',
      'Khoá học trọng tâm trang bị chiến lược xử lý bẫy Listening Part 1-4 và ngữ pháp đọc hiểu Part 5-7 kèm ngân hàng đề thi thử thực chiến.',
      'Đội ngũ Học thuật English9x', 'FREE', v_admin_id);

    -- Chapters
    INSERT INTO course_chapters(course_version_id, position, title, pass_threshold, exam_code)
    VALUES
      (v_c1_ver_id, 1, 'Listening Part 1 - Bẫy hình ảnh & Hành động', 0.7, 'TOEIC'),
      (v_c1_ver_id, 2, 'Reading Part 5 - Ngữ pháp trọng điểm & Từ loại', 0.7, 'TOEIC'),
      (v_c1_ver_id, 3, 'Reading Part 7 - Kỹ thuật Skimming & Scanning', 0.7, 'TOEIC');

    -- Lessons
    INSERT INTO course_lessons(course_version_id, chapter_position, position, kind, ref_id, title, exam_code)
    VALUES
      (v_c1_ver_id, 1, 1, 'LESSON', v_l1_v, 'Bài 1: Phân tích tranh miêu tả hành động người', 'TOEIC'),
      (v_c1_ver_id, 1, 2, 'LESSON', v_l2_v, 'Bài 2: Bẫy tranh miêu tả đồ vật và không gian tĩnh', 'TOEIC'),
      (v_c1_ver_id, 2, 1, 'LESSON', v_l3_v, 'Bài 3: Mẹo nhận diện Từ loại nhanh trong 5 giây', 'TOEIC'),
      (v_c1_ver_id, 2, 2, 'LESSON', v_l4_v, 'Bài 4: Phân biệt By vs Until & Giới từ chỉ mốc thời gian', 'TOEIC');

    IF v_test_ver_id IS NOT NULL THEN
      INSERT INTO course_lessons(course_version_id, chapter_position, position, kind, ref_id, title, exam_code)
      VALUES
        (v_c1_ver_id, 2, 3, 'CHAPTER_TEST', v_test_ver_id, 'Kiểm tra chương 2: Mini-Test Part 5', 'TOEIC'),
        (v_c1_ver_id, 3, 1, 'LESSON', v_l5_v, 'Bài 5: Kỹ thuật nối thông tin đoạn kép và đoạn ba Part 7', 'TOEIC'),
        (v_c1_ver_id, 3, 2, 'MOCK_TEST', v_test_ver_id, 'Thi thử tổng hợp: TOEIC Mini-Test 2026', 'TOEIC');
    ELSE
      INSERT INTO course_lessons(course_version_id, chapter_position, position, kind, ref_id, title, exam_code)
      VALUES (v_c1_ver_id, 3, 1, 'LESSON', v_l5_v, 'Bài 5: Kỹ thuật nối thông tin đoạn kép và đoạn ba Part 7', 'TOEIC');
    END IF;

    UPDATE course_versions SET status = 'PUBLISHED' WHERE id = v_c1_ver_id;
  END IF;

  -- 4.2 Khóa học IEL700
  IF NOT EXISTS (SELECT 1 FROM courses WHERE code = 'IEL700') THEN
    v_c2_id := gen_random_uuid(); v_c2_ver_id := gen_random_uuid();
    INSERT INTO courses(id, exam_code, code, title, status, created_by)
    VALUES (v_c2_id, 'IELTS', 'IEL700', 'IELTS Academic: Bứt Phá Band 7.0+ 4 Kỹ Năng', 'DRAFT', v_admin_id);

    INSERT INTO course_versions(id, course_id, exam_code, version_no, status, title, description, author_display, unlock_rule, created_by)
    VALUES (v_c2_ver_id, v_c2_id, 'IELTS', 1, 'DRAFT', 'IELTS Academic: Bứt Phá Band 7.0+ 4 Kỹ Năng',
      'Lộ trình chuyên sâu Academic Writing Task 1 & 2 với tiêu chuẩn chấm AI, Speaking phản xạ tự nhiên và Listening - Reading tốc độ cao.',
      'Thầy Mark Robertson & Cô Minh Anh', 'FREE', v_admin_id);

    INSERT INTO course_chapters(course_version_id, position, title, pass_threshold, exam_code)
    VALUES
      (v_c2_ver_id, 1, 'Writing Task 1 - Biểu đồ số liệu & Xu hướng', 0.7, 'IELTS'),
      (v_c2_ver_id, 2, 'Writing Task 2 - Xây dựng luận điểm Coherence', 0.7, 'IELTS'),
      (v_c2_ver_id, 3, 'Speaking Part 2 - Mở rộng câu trả lời tự nhiên', 0.7, 'IELTS');

    INSERT INTO course_lessons(course_version_id, chapter_position, position, kind, ref_id, title, exam_code)
    VALUES
      (v_c2_ver_id, 1, 1, 'LESSON', v_l6_v, 'Bài 1: Cấu trúc bài viết Writing Task 1 chuẩn Band 7.0+', 'IELTS'),
      (v_c2_ver_id, 2, 1, 'LESSON', v_l7_v, 'Bài 2: Phát triển đoạn văn Task 2 theo phương pháp PEEL', 'IELTS'),
      (v_c2_ver_id, 3, 1, 'LESSON', v_l8_v, 'Bài 3: Kỹ thuật mở rộng câu trả lời Speaking Part 2', 'IELTS');

    UPDATE course_versions SET status = 'PUBLISHED' WHERE id = v_c2_ver_id;
  END IF;

  -- ==============================================================================
  -- 5. LỊCH SỬ THI MẪU & THỐNG KÊ ĐIỂM YẾU CHO HỌC VIÊN (hocvien@english9x.vn)
  -- ==============================================================================
  IF v_learner_id IS NOT NULL AND v_test_ver_id IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM exam_attempts WHERE user_id = v_learner_id) THEN
      v_attempt_id := gen_random_uuid();

      -- Create completed attempt
      INSERT INTO exam_attempts(
        id, owner_org_id, test_version_id, user_id, started_at, status, submitted_at,
        exam_code, max_resumes, resume_count, interrupted, submit_reason, current_section_index, version
      ) VALUES (
        v_attempt_id, NULL, v_test_ver_id, v_learner_id, now() - interval '2 days', 'SUBMITTED', now() - interval '2 days' + interval '12 minutes',
        'TOEIC', 3, 0, false, 'LEARNER', 0, 1
      );

      -- Sections
      INSERT INTO attempt_sections(attempt_id, position, user_id, section_code, skill, duration_sec, starts_at, ends_at)
      VALUES (v_attempt_id, 0, v_learner_id, 'P5', 'READING', 900, now() - interval '2 days', now() - interval '2 days' + interval '12 minutes');

      -- Scores
      INSERT INTO scores(attempt_id, user_id, owner_org_id, exam_code, skill, kind, source, raw, raw_max, scaled, score_table_version_id)
      VALUES
        (v_attempt_id, v_learner_id, NULL, 'TOEIC', 'READING', 'SCALED', 'AUTO', 3, 4, 290, v_table_ver_id),
        (v_attempt_id, v_learner_id, NULL, 'TOEIC', 'LISTENING', 'SCALED', 'AUTO', 25, 30, 320, NULL),
        (v_attempt_id, v_learner_id, NULL, 'TOEIC', 'TOTAL', 'TOTAL', 'AUTO', 28, 34, 610, NULL)
      ON CONFLICT DO NOTHING;

      -- Attempt grading status
      INSERT INTO attempt_gradings(attempt_id, interrupted, announced_at)
      VALUES (v_attempt_id, false, now() - interval '2 days')
      ON CONFLICT DO NOTHING;

      -- Weakness stats: 4 câu hỏi Part 5 (3 đúng, 1 sai) và 2 câu Part 1
      IF v_q1_v IS NOT NULL THEN
        INSERT INTO item_type_stats(attempt_id, question_version_id, user_id, owner_org_id, exam_code, type_code, skill, earned, max_points, correct, created_at)
        VALUES
          (v_attempt_id, v_q1_v, v_learner_id, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 'READING', 1, 1, true, now() - interval '2 days'),
          (v_attempt_id, v_q2_v, v_learner_id, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 'READING', 1, 1, true, now() - interval '2 days'),
          (v_attempt_id, v_q3_v, v_learner_id, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 'READING', 1, 1, true, now() - interval '2 days'),
          (v_attempt_id, v_q4_v, v_learner_id, NULL, 'TOEIC', 'TOEIC_P5_INCOMPLETE_SENTENCE', 'READING', 0, 1, false, now() - interval '2 days')
        ON CONFLICT DO NOTHING;
      END IF;

      RAISE NOTICE 'Đã tạo lịch sử thi và thống kê điểm yếu mẫu cho học viên hocvien@english9x.vn!';
    END IF;
  END IF;

  -- ==============================================================================
  -- 6. GÓI HỌC B2C (Plans) VÀ ĐƠN HÀNG MẪU (Orders)
  -- ==============================================================================
  INSERT INTO plans (name, price_vnd, months, max_courses, active) VALUES
    ('Gói Luyện Thi 1 Tháng (1 Khóa học)', 299000, 1, 1, true),
    ('Gói Bứt Phá 3 Tháng (3 Khóa học)', 699000, 3, 3, true),
    ('Gói Toàn Diện 12 Tháng (Không giới hạn)', 1999000, 12, 10, true)
  ON CONFLICT DO NOTHING;

  -- Tạo đơn hàng đã thanh toán mẫu nếu có học viên
  IF v_learner_id IS NOT NULL THEN
    SELECT id INTO v_plan_id FROM plans WHERE months = 3 LIMIT 1;
    IF v_plan_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM orders WHERE user_id = v_learner_id) THEN
      INSERT INTO orders(code, user_id, plan_id, plan_months, amount_vnd, status, paid_at, created_at)
      VALUES (
        'ORD-SAMPLE-2026', v_learner_id, v_plan_id, 3, 699000, 'PAID', now() - interval '10 days', now() - interval '10 days'
      ) ON CONFLICT DO NOTHING;
    END IF;
  END IF;

  RAISE NOTICE '✨ Toàn bộ dữ liệu mẫu đã được nạp thành công vào hệ thống!';
END $$;
