-- English9x: Tạo dữ liệu mẫu khóa học đầy đủ (Courses, Chapters, Lessons)
-- Chạy với vai trò e9x_owner trong PostgreSQL của Docker
DO $$
DECLARE
  v_admin_id uuid;
  v_c1_id uuid;
  v_c1_ver_id uuid;
  v_c2_id uuid;
  v_c2_ver_id uuid;

  v_l1 uuid; v_l1_v uuid;
  v_l2 uuid; v_l2_v uuid;
  v_l3 uuid; v_l3_v uuid;
  v_l4 uuid; v_l4_v uuid;
  v_l5 uuid; v_l5_v uuid;
  v_l6 uuid; v_l6_v uuid;
  v_l7 uuid; v_l7_v uuid;
  v_l8 uuid; v_l8_v uuid;
BEGIN
  -- Lấy ID của tác giả hoặc admin
  SELECT id INTO v_admin_id FROM users WHERE email = 'author@english9x.vn';
  IF v_admin_id IS NULL THEN
    SELECT id INTO v_admin_id FROM users WHERE email = 'admin@english9x.vn';
  END IF;
  IF v_admin_id IS NULL THEN
    SELECT id INTO v_admin_id FROM users ORDER BY created_at LIMIT 1;
  END IF;

  IF v_admin_id IS NULL THEN
    RAISE NOTICE 'Chưa có tài khoản người dùng để gán tác giả. Hãy chạy seed-sample-data.mjs trước!';
    RETURN;
  END IF;

  -- 1. TẠO CÁC BÀI HỌC PHƯƠNG PHÁP (Strategy Lessons) CHO TOEIC
  IF NOT EXISTS (SELECT 1 FROM courses WHERE code = 'TOE650') THEN
    -- Lesson 1
    v_l1 := gen_random_uuid(); v_l1_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l1, 'TOEIC', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l1_v, v_l1, 'TOEIC', 1, 'PUBLISHED', 'Chiến thuật phân tích tranh miêu tả hành động người',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Phương pháp quan sát tổng quan 3 giây'),
        jsonb_build_object('type', 'paragraph', 'text', 'Khi nhìn tranh có người, lập tức xác định: Chủ thể (Ai?) - Hành động (Đang làm gì?) - Bối cảnh (Ở đâu?). Chú ý các động từ thể hiện hành động tiếp diễn (be + V-ing) như holding, looking at, typing on.')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l1_v, 'TOEIC_P1_PHOTO', 'TOEIC');

    -- Lesson 2
    v_l2 := gen_random_uuid(); v_l2_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l2, 'TOEIC', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l2_v, v_l2, 'TOEIC', 1, 'PUBLISHED', 'Bẫy tranh miêu tả đồ vật và không gian tĩnh',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Bẫy bị động Being + PII'),
        jsonb_build_object('type', 'paragraph', 'text', 'Nếu trong tranh KHÔNG có người, loại ngay các đáp án có thì Hiện tại tiếp diễn bị động (is/are being + V-ed) vì cấu trúc này diễn tả hành động đang được thực hiện bởi người.')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l2_v, 'TOEIC_P1_PHOTO', 'TOEIC');

    -- Lesson 3
    v_l3 := gen_random_uuid(); v_l3_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l3, 'TOEIC', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l3_v, v_l3, 'TOEIC', 1, 'PUBLISHED', 'Mẹo nhận diện Từ loại nhanh trong 5 giây',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Vị trí của Danh từ và Tính từ'),
        jsonb_build_object('type', 'paragraph', 'text', 'Đứng trước danh từ thường là tính từ (Adj + N). Đứng sau mạo từ a/an/the hoặc tính từ sở hữu (our, their, my) bắt buộc là danh từ.')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l3_v, 'TOEIC_P5_INCOMPLETE_SENTENCE', 'TOEIC');

    -- Lesson 4
    v_l4 := gen_random_uuid(); v_l4_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l4, 'TOEIC', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l4_v, v_l4, 'TOEIC', 1, 'PUBLISHED', 'Phân biệt By vs Until & Giới từ chỉ mốc thời gian',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Quy tắc By và Until'),
        jsonb_build_object('type', 'paragraph', 'text', 'By diễn tả hành động hoàn tất TRƯỚC một hạn chót (submit report by 5 PM). Until diễn tả trạng thái kéo dài LIÊN TỤC cho tới mốc đó (the store is open until 9 PM).')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l4_v, 'TOEIC_P5_INCOMPLETE_SENTENCE', 'TOEIC');

    -- Lesson 5
    v_l5 := gen_random_uuid(); v_l5_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l5, 'TOEIC', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l5_v, v_l5, 'TOEIC', 1, 'PUBLISHED', 'Kỹ thuật nối thông tin đoạn kép và đoạn ba Part 7',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Cross-reference Linking Strategy'),
        jsonb_build_object('type', 'paragraph', 'text', 'Câu hỏi suy luận liên đoạn văn luôn cần ghép 2 mảnh ghép: Đọc câu hỏi -> tìm từ khóa ở đoạn 1 -> đối chiếu bảng thông tin/hóa đơn ở đoạn 2 để ra đáp án.')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l5_v, 'TOEIC_P7_MULTIPLE_PASSAGES', 'TOEIC');

    -- 2. TẠO KHÓA HỌC TOEIC 450 - 650 (TOE650)
    v_c1_id := gen_random_uuid(); v_c1_ver_id := gen_random_uuid();
    INSERT INTO courses(id, exam_code, code, title, status, created_by)
    VALUES (v_c1_id, 'TOEIC', 'TOE650', 'TOEIC 450 - 650+: Chiến Thuật Toàn Diện 7 Parts', 'DRAFT', v_admin_id);

    INSERT INTO course_versions(id, course_id, exam_code, version_no, status, title, description, author_display, unlock_rule, created_by)
    VALUES (v_c1_ver_id, v_c1_id, 'TOEIC', 1, 'DRAFT', 'TOEIC 450 - 650+: Chiến Thuật Toàn Diện 7 Parts',
      'Khoá học trọng tâm trang bị chiến lược xử lý bẫy Listening Part 1-4 và ngữ pháp đọc hiểu Part 5-7 kèm ngân hàng đề thi thử thực chiến.',
      'Đội ngũ Học thuật English9x', 'FREE', v_admin_id);

    -- Chapters for TOE650
    INSERT INTO course_chapters(course_version_id, position, title, pass_threshold, exam_code)
    VALUES
      (v_c1_ver_id, 1, 'Chương 1: Listening Part 1 - Bẫy hình ảnh & Hành động', 0.7, 'TOEIC'),
      (v_c1_ver_id, 2, 'Chương 2: Reading Part 5 - Ngữ pháp trọng điểm & Từ loại', 0.7, 'TOEIC'),
      (v_c1_ver_id, 3, 'Chương 3: Reading Part 7 - Kỹ thuật Skimming & Scanning', 0.7, 'TOEIC');

    -- Lessons for TOE650
    INSERT INTO course_lessons(course_version_id, chapter_position, position, kind, ref_id, title, exam_code)
    VALUES
      (v_c1_ver_id, 1, 1, 'LESSON', v_l1_v, 'Bài 1: Phân tích tranh miêu tả hành động người', 'TOEIC'),
      (v_c1_ver_id, 1, 2, 'LESSON', v_l2_v, 'Bài 2: Bẫy tranh miêu tả đồ vật và không gian tĩnh', 'TOEIC'),
      (v_c1_ver_id, 2, 1, 'LESSON', v_l3_v, 'Bài 3: Mẹo nhận diện Từ loại nhanh trong 5 giây', 'TOEIC'),
      (v_c1_ver_id, 2, 2, 'LESSON', v_l4_v, 'Bài 4: Phân biệt By vs Until & Giới từ chỉ mốc thời gian', 'TOEIC'),
      (v_c1_ver_id, 3, 1, 'LESSON', v_l5_v, 'Bài 5: Kỹ thuật nối thông tin đoạn kép và đoạn ba Part 7', 'TOEIC');

    -- Publish the course version
    UPDATE course_versions SET status = 'PUBLISHED' WHERE id = v_c1_ver_id;
    RAISE NOTICE 'Đã tạo thành công khóa học TOEIC (TOE650) với 3 chương và 5 bài học!';
  END IF;

  -- 3. TẠO KHÓA HỌC IELTS ACADEMIC BAND 7.0+ (IEL700)
  IF NOT EXISTS (SELECT 1 FROM courses WHERE code = 'IEL700') THEN
    -- IELTS Lessons
    v_l6 := gen_random_uuid(); v_l6_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l6, 'IELTS', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l6_v, v_l6, 'IELTS', 1, 'PUBLISHED', 'Cấu trúc bài viết Writing Task 1 chuẩn Band 7.0+',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Bố cục 4 đoạn chuẩn'),
        jsonb_build_object('type', 'paragraph', 'text', 'Bố cục gồm: Introduction (Paraphrase đề bài) - Overview (2 đặc điểm nổi bật nhất) - Body 1 (Nhóm số liệu chi tiết) - Body 2 (Nhóm số liệu so sánh).')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l6_v, 'IELTS_W_AC_TASK1', 'IELTS');

    v_l7 := gen_random_uuid(); v_l7_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l7, 'IELTS', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l7_v, v_l7, 'IELTS', 1, 'PUBLISHED', 'Phát triển đoạn văn Task 2 theo phương pháp PEEL',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Phương pháp PEEL'),
        jsonb_build_object('type', 'paragraph', 'text', 'Point (Luận điểm chính) - Explanation (Giải thích nguyên nhân) - Example (Dẫn chứng cụ thể) - Link (Câu chốt liên kết lại đề tài).')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l7_v, 'IELTS_W_TASK2', 'IELTS');

    v_l8 := gen_random_uuid(); v_l8_v := gen_random_uuid();
    INSERT INTO strategy_lessons(id, exam_code, created_by) VALUES (v_l8, 'IELTS', v_admin_id);
    INSERT INTO strategy_lesson_versions(id, lesson_id, exam_code, version_no, status, title, blocks, created_by)
    VALUES (v_l8_v, v_l8, 'IELTS', 1, 'PUBLISHED', 'Kỹ thuật mở rộng câu trả lời Speaking Part 2',
      jsonb_build_array(
        jsonb_build_object('type', 'heading', 'level', 2, 'text', '1. Kể chuyện theo trình tự thời gian'),
        jsonb_build_object('type', 'paragraph', 'text', 'Tận dụng 1 phút chuẩn bị ghi lại 4-5 gạch đầu dòng theo quy tắc 5W1H (Who, When, Where, What, Why).')
      ), v_admin_id);
    INSERT INTO strategy_lesson_types(version_id, type_code, exam_code) VALUES (v_l8_v, 'IELTS_S_PART2', 'IELTS');

    -- Create Course IEL700
    v_c2_id := gen_random_uuid(); v_c2_ver_id := gen_random_uuid();
    INSERT INTO courses(id, exam_code, code, title, status, created_by)
    VALUES (v_c2_id, 'IELTS', 'IEL700', 'IELTS Academic: Bứt Phá Band 7.0+ 4 Kỹ Năng', 'DRAFT', v_admin_id);

    INSERT INTO course_versions(id, course_id, exam_code, version_no, status, title, description, author_display, unlock_rule, created_by)
    VALUES (v_c2_ver_id, v_c2_id, 'IELTS', 1, 'DRAFT', 'IELTS Academic: Bứt Phá Band 7.0+ 4 Kỹ Năng',
      'Lộ trình chuyên sâu Academic Writing Task 1 & 2 với tiêu chuẩn chấm AI, Speaking phản xạ tự nhiên và Listening - Reading tốc độ cao.',
      'Thầy Mark Robertson & Cô Minh Anh', 'FREE', v_admin_id);

    -- Chapters for IEL700
    INSERT INTO course_chapters(course_version_id, position, title, pass_threshold, exam_code)
    VALUES
      (v_c2_ver_id, 1, 'Chương 1: Writing Task 1 - Biểu đồ số liệu & Xu hướng', 0.7, 'IELTS'),
      (v_c2_ver_id, 2, 'Chương 2: Writing Task 2 - Xây dựng luận điểm Coherence', 0.7, 'IELTS'),
      (v_c2_ver_id, 3, 'Chương 3: Speaking Part 2 - Mở rộng câu trả lời tự nhiên', 0.7, 'IELTS');

    -- Lessons for IEL700
    INSERT INTO course_lessons(course_version_id, chapter_position, position, kind, ref_id, title, exam_code)
    VALUES
      (v_c2_ver_id, 1, 1, 'LESSON', v_l6_v, 'Bài 1: Cấu trúc bài viết Writing Task 1 chuẩn Band 7.0+', 'IELTS'),
      (v_c2_ver_id, 2, 1, 'LESSON', v_l7_v, 'Bài 2: Phát triển đoạn văn Task 2 theo phương pháp PEEL', 'IELTS'),
      (v_c2_ver_id, 3, 1, 'LESSON', v_l8_v, 'Bài 3: Kỹ thuật mở rộng câu trả lời Speaking Part 2', 'IELTS');

    -- Publish the course version
    UPDATE course_versions SET status = 'PUBLISHED' WHERE id = v_c2_ver_id;
    RAISE NOTICE 'Đã tạo thành công khóa học IELTS (IEL700) với 3 chương và 3 bài học!';
  END IF;

END $$;
