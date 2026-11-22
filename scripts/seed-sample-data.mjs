#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Accept Caddy internal self-signed certificate for localhost
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const ENV_PATH = path.join(ROOT, 'infra', '.env');

// 1. Read E9X_E2E_SEED_SECRET from infra/.env
let seedSecret = process.env.E9X_E2E_SEED_SECRET;
if (!seedSecret && fs.existsSync(ENV_PATH)) {
  const envContent = fs.readFileSync(ENV_PATH, 'utf8');
  for (const line of envContent.split('\n')) {
    const match = line.match(/^E9X_E2E_SEED_SECRET=(.*)$/);
    if (match) {
      seedSecret = match[1].trim().replace(/^['"]|['"]$/g, '');
      break;
    }
  }
}

if (!seedSecret) {
  console.error('❌ Lỗi: Không tìm thấy E9X_E2E_SEED_SECRET trong infra/.env hoặc biến môi trường.');
  console.error('👉 Vui lòng chạy lệnh: echo "E9X_E2E_SEED_SECRET=$(openssl rand -hex 24)" >> infra/.env');
  process.exit(1);
}

const BASE_URL = process.env.BASE_URL || 'https://localhost';
const DEFAULT_PASSWORD = process.env.SAMPLE_PASSWORD || 'Password123!';

async function post(endpoint, body) {
  const url = `${BASE_URL}/api/test/seed/${endpoint}`;
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-E2E-Seed-Secret': seedSecret,
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 409) {
      return { status: 409, conflict: true };
    }
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`HTTP ${res.status}: ${text}`);
    }
    return await res.json();
  } catch (err) {
    if (err.message && err.message.includes('fetch failed')) {
      throw new Error(`Không thể kết nối đến ${BASE_URL}. Hãy chắc chắn container english9x-caddy-1 và english9x-app-1 đang chạy!`);
    }
    throw err;
  }
}

async function getOrCreateLearner(email, displayName) {
  const res = await post('learner', {
    email,
    password: DEFAULT_PASSWORD,
    displayName,
    verified: true,
    locale: 'vi',
  });
  if (res.conflict) {
    const info = await post('user-info', { email });
    return { userId: info.id, email, password: DEFAULT_PASSWORD, existing: true };
  }
  return { userId: res.userId, email, password: DEFAULT_PASSWORD, existing: false };
}

async function main() {
  console.log(`\n======================================================`);
  console.log(`🌱 ĐANG KHỞI TẠO DỮ LIỆU MẪU (SAMPLE DATA) CHO ENGLISH9X`);
  console.log(`🎯 Địa chỉ: ${BASE_URL}`);
  console.log(`🔑 Mật khẩu chung: ${DEFAULT_PASSWORD}`);
  console.log(`======================================================\n`);

  // 1. Kiểm tra kết nối Seed API
  try {
    const ping = await post('ping');
    console.log(`✅ Kết nối Seed API thành công (Database login: ${ping.seedLogin})\n`);
  } catch (err) {
    console.error(`❌ Lỗi kết nối: ${err.message}`);
    process.exit(1);
  }

  // 2. Tạo tài khoản hệ thống (Staff & Admin)
  console.log(`--- [1/4] Tạo tài khoản người dùng & Phân quyền ---`);

  // 2.1 SYS_ADMIN
  const admin = await getOrCreateLearner('admin@english9x.vn', 'Quản trị viên Hệ thống');
  await post('staff-grant', { userId: admin.userId, role: 'SYS_ADMIN' });
  console.log(`✔ [SYS_ADMIN]  ${admin.email} (Quyền Quản trị viên tối cao)`);

  // 2.2 AUTHOR (TOEIC & IELTS)
  const author = await getOrCreateLearner('author@english9x.vn', 'Nguyễn Tác Giả (CMS)');
  await post('staff-grant', { userId: author.userId, role: 'AUTHOR', examCode: 'TOEIC' });
  await post('staff-grant', { userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
  console.log(`✔ [AUTHOR]     ${author.email} (Soạn đề thi TOEIC & IELTS)`);

  // 2.3 REVIEWER (TOEIC & IELTS)
  const reviewer = await getOrCreateLearner('reviewer@english9x.vn', 'Trần Kiểm Duyệt');
  await post('staff-grant', { userId: reviewer.userId, role: 'REVIEWER', examCode: 'TOEIC' });
  await post('staff-grant', { userId: reviewer.userId, role: 'REVIEWER', examCode: 'IELTS' });
  console.log(`✔ [REVIEWER]   ${reviewer.email} (Kiểm duyệt đề thi TOEIC & IELTS)`);

  // 2.4 TEACHER (TOEIC & IELTS)
  const teacher = await getOrCreateLearner('teacher@english9x.vn', 'Lê Giảng Viên');
  await post('staff-grant', { userId: teacher.userId, role: 'TEACHER', examCode: 'TOEIC' });
  await post('staff-grant', { userId: teacher.userId, role: 'TEACHER', examCode: 'IELTS' });
  console.log(`✔ [TEACHER]    ${teacher.email} (Chấm điểm Writing/Speaking)`);

  // 2.5 LEARNER
  const learner = await getOrCreateLearner('hocvien@english9x.vn', 'Hoàng Học Viên');
  console.log(`✔ [LEARNER]    ${learner.email} (Học viên mẫu)`);

  // 3. Tạo Tổ chức B2B mẫu
  console.log(`\n--- [2/4] Tạo Tổ chức Giáo dục B2B ---`);
  const org = await post('organization', { name: 'Trung Tâm Anh Ngữ VinaStar', type: 'CENTER' });
  console.log(`✔ [TỔ CHỨC]   ${org.name} (Loại: ${org.type}, ID: ${org.orgId})`);

  // Tạo nhân sự của Trung tâm VinaStar
  const orgAdmin = await getOrCreateLearner('admin@vinastar.edu.vn', 'Quản trị Trung tâm VinaStar');
  await post('membership', {
    orgId: org.orgId,
    userId: orgAdmin.userId,
    roles: [{ role: 'ORG_ADMIN', examCode: null }],
  });
  console.log(`  └─ [ORG_ADMIN] ${orgAdmin.email}`);

  const orgTeacher = await getOrCreateLearner('teacher@vinastar.edu.vn', 'Cô Mai VinaStar');
  await post('membership', {
    orgId: org.orgId,
    userId: orgTeacher.userId,
    roles: [{ role: 'TEACHER', examCode: 'TOEIC' }],
  });
  console.log(`  └─ [TEACHER]   ${orgTeacher.email} (Giáo viên TOEIC)`);

  const orgStudent = await getOrCreateLearner('student@vinastar.edu.vn', 'Học viên VinaStar');
  await post('membership', {
    orgId: org.orgId,
    userId: orgStudent.userId,
    roles: [{ role: 'LEARNER', examCode: null }],
  });
  console.log(`  └─ [LEARNER]   ${orgStudent.email}`);

  // 4. Tạo Khóa học & Cấp quyền học (Courses & Entitlements)
  console.log(`\n--- [3/4] Tạo Khóa học & Cấp quyền học ---`);
  const toeicCourse = await post('billing/course', { examCode: 'TOEIC' });
  console.log(`✔ [KHÓA HỌC]   TOEIC Course (Mã: ${toeicCourse.code})`);

  const ieltsCourse = await post('billing/course', { examCode: 'IELTS' });
  console.log(`✔ [KHÓA HỌC]   IELTS Course (Mã: ${ieltsCourse.code})`);

  // Cấp quyền học cho tài khoản hocvien@english9x.vn (60 ngày)
  await post('billing/entitlement', { userId: learner.userId, courseId: toeicCourse.courseId, endsInDays: 60 });
  await post('billing/entitlement', { userId: learner.userId, courseId: ieltsCourse.courseId, endsInDays: 60 });
  console.log(`✔ [QUYỀN HỌC]  Đã cấp quyền học 60 ngày cho ${learner.email} trên cả 2 khóa học!`);

  // 5. Bảng tổng hợp tài khoản mẫu
  console.log(`\n======================================================`);
  console.log(`🎉 HOÀN THÀNH TẠO DỮ LIỆU MẪU!`);
  console.log(`======================================================\n`);
  console.log(`BẢNG TÀI KHOẢN MẪU ĐỂ ĐĂNG NHẬP (Mật khẩu: ${DEFAULT_PASSWORD}):\n`);
  console.log(`| Vai trò (Role)       | Email                    | Quyền hạn chính                          |`);
  console.log(`|----------------------|--------------------------|------------------------------------------|`);
  console.log(`| Quản trị tối cao     | admin@english9x.vn       | SYS_ADMIN: Toàn quyền hệ thống, B2B, CMS  |`);
  console.log(`| Tác giả biên soạn    | author@english9x.vn      | AUTHOR: Soạn câu hỏi & đề thi TOEIC/IELTS |`);
  console.log(`| Người duyệt đề       | reviewer@english9x.vn    | REVIEWER: Duyệt đề thi trước xuất bản   |`);
  console.log(`| Giáo viên chấm điểm  | teacher@english9x.vn     | TEACHER: Chấm bài Writing & Speaking     |`);
  console.log(`| Học viên mẫu         | hocvien@english9x.vn     | LEARNER: Đã mở sẵn 2 khóa TOEIC & IELTS  |`);
  console.log(`| Admin Trung tâm B2B  | admin@vinastar.edu.vn    | ORG_ADMIN: Quản lý Trung tâm VinaStar    |`);
  console.log(`| GV Trung tâm B2B     | teacher@vinastar.edu.vn  | TEACHER: Giáo viên trực thuộc VinaStar   |`);
  console.log(`| HV Trung tâm B2B     | student@vinastar.edu.vn  | LEARNER: Học viên thuộc Trung tâm        |`);
  console.log(`\n👉 Đăng nhập ngay tại: ${BASE_URL}/login\n`);
}

main().catch((err) => {
  console.error('\n❌ Có lỗi xảy ra khi tạo sample data:', err);
  process.exit(1);
});
