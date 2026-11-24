import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, Button } from '../../design';

export function LearnerHomePage() {
  const { t } = useTranslation();

  return (
    <div className="flex flex-col gap-16 py-4">
      {/* 1. HERO SECTION */}
      <section className="relative overflow-hidden rounded-2xl border border-line bg-surface p-8 shadow-card sm:p-12 lg:p-16">
        <div className="max-w-3xl">
          <div className="inline-flex items-center gap-2 rounded-full border border-line bg-sunken px-3.5 py-1 text-xs font-semibold text-primary">
            <span className="flex h-2 w-2 rounded-full bg-accent animate-pulse" />
            <span>NỀN TẢNG LUYỆN THI THẾ HỆ MỚI • AI GRADER 24/7</span>
          </div>

          <h1 className="mt-6 font-sans text-4xl font-black tracking-tight text-ink sm:text-5xl lg:text-6xl">
            {t('app.name')}
          </h1>
          <p className="mt-3 font-sans text-lg font-medium text-primary sm:text-xl">
            {t('app.tagline')}
          </p>

          <p className="mt-6 text-base leading-relaxed text-muted sm:text-lg">
            Hệ thống luyện thi chuyên sâu chuẩn khảo thí ETS & Cambridge. Trải nghiệm phòng thi mô phỏng 100% thời gian thực,
            công nghệ AI chấm Writing & Speaking chi tiết theo tiêu chuẩn quốc tế và hệ thống radar định vị điểm yếu tự động.
          </p>

          <div className="mt-8 flex flex-wrap items-center gap-4">
            <Link to="/courses">
              <Button size="lg" className="px-6 py-3 text-base shadow-sm font-bold">
                Khám phá khoá học & đề thi →
              </Button>
            </Link>
            <Link to="/login">
              <Button variant="secondary" size="lg" className="px-6 py-3 text-base font-semibold">
                Đăng nhập tài khoản
              </Button>
            </Link>
          </div>
        </div>

        {/* 3 Key feature highlights inside hero */}
        <div className="mt-12 grid gap-4 border-t border-line pt-8 sm:grid-cols-3">
          <div className="flex items-start gap-3 rounded-xl p-3 transition-colors hover:bg-sunken/50">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-info-bg text-info font-bold text-lg">
              🎯
            </div>
            <div>
              <h3 className="font-bold text-ink">Chuẩn đề thi thật</h3>
              <p className="mt-1 text-xs text-muted leading-relaxed">Cấu trúc 7 Parts TOEIC & 4 kỹ năng IELTS cập nhật theo format mới nhất.</p>
            </div>
          </div>

          <div className="flex items-start gap-3 rounded-xl p-3 transition-colors hover:bg-sunken/50">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-tip-bg text-tip font-bold text-lg">
              ⚡
            </div>
            <div>
              <h3 className="font-bold text-ink">AI Grader tức thì</h3>
              <p className="mt-1 text-xs text-muted leading-relaxed">Chấm điểm tự luận Writing & Speaking phân tích theo từng tiêu chí chấm.</p>
            </div>
          </div>

          <div className="flex items-start gap-3 rounded-xl p-3 transition-colors hover:bg-sunken/50">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-success-bg text-success font-bold text-lg">
              📊
            </div>
            <div>
              <h3 className="font-bold text-ink">Radar điểm yếu</h3>
              <p className="mt-1 text-xs text-muted leading-relaxed">Tự động phát hiện dạng câu hỏi sai nhiều và gợi ý bài giảng khắc phục.</p>
            </div>
          </div>
        </div>
      </section>

      {/* 2. STATS & PROOF SECTION */}
      <section className="grid grid-cols-2 gap-4 rounded-xl border border-line bg-sunken p-6 sm:grid-cols-4 sm:p-8">
        <div className="flex flex-col items-center text-center">
          <span className="font-sans text-3xl font-black text-primary sm:text-4xl">100.000+</span>
          <span className="mt-1 text-xs font-semibold text-muted sm:text-sm">Lượt thi thử & luyện tập</span>
        </div>
        <div className="flex flex-col items-center text-center">
          <span className="font-sans text-3xl font-black text-primary sm:text-4xl">98.5%</span>
          <span className="mt-1 text-xs font-semibold text-muted sm:text-sm">Học viên bứt phá mục tiêu</span>
        </div>
        <div className="flex flex-col items-center text-center">
          <span className="font-sans text-3xl font-black text-primary sm:text-4xl">5.000+</span>
          <span className="mt-1 text-xs font-semibold text-muted sm:text-sm">Câu hỏi có lời giải chi tiết</span>
        </div>
        <div className="flex flex-col items-center text-center">
          <span className="font-sans text-3xl font-black text-primary sm:text-4xl">24/7</span>
          <span className="mt-1 text-xs font-semibold text-muted sm:text-sm">AI Grader sẵn sàng chấm bài</span>
        </div>
      </section>

      {/* 3. CORE EXAM TRACKS (TOEIC & IELTS) */}
      <section className="flex flex-col gap-6">
        <div>
          <h2 className="font-sans text-2xl font-black text-ink sm:text-3xl tracking-tight">Lộ Trình Luyện Thi Trọng Điểm</h2>
          <p className="mt-1 text-muted text-sm sm:text-base">Chọn chương trình học phù hợp với mục tiêu chứng chỉ quốc tế của bạn.</p>
        </div>

        <div className="grid gap-6 md:grid-cols-2">
          {/* TOEIC TRACK */}
          <div className="group flex flex-col justify-between rounded-2xl border border-line bg-surface p-6 sm:p-8 shadow-card transition-all hover:border-primary hover:shadow-hover">
            <div className="flex flex-col gap-4">
              <div className="flex items-center justify-between">
                <Badge className="bg-info-bg text-info font-bold px-3 py-1">TOEIC Listening & Reading</Badge>
                <span className="text-xs font-semibold text-muted">Mục tiêu 450 - 900+</span>
              </div>
              <h3 className="font-sans text-xl font-bold text-ink group-hover:text-primary transition-colors">Chinh Phục 7 Parts TOEIC Thực Chiến</h3>
              <p className="text-sm text-muted leading-relaxed">
                Chiến thuật phân tích bẫy Listening Part 1-4, quy tắc xử lý ngữ pháp Part 5 trong 5 giây, và phương pháp
                Skimming & Scanning đoạn kép, đoạn ba Part 7.
              </p>
              <ul className="flex flex-col gap-2.5 text-xs text-ink pt-2">
                <li className="flex items-center gap-2">
                  <span className="text-success font-bold text-sm">✔</span> Ngân hàng đề ETS format mới có Audio & Transcript
                </li>
                <li className="flex items-center gap-2">
                  <span className="text-success font-bold text-sm">✔</span> Bảng quy đổi điểm chuẩn quốc tế và tự động chấm
                </li>
                <li className="flex items-center gap-2">
                  <span className="text-success font-bold text-sm">✔</span> Luyện tập từng phần (Mini-Test) và Full Test 200 câu
                </li>
              </ul>
            </div>
            <div className="mt-6 border-t border-line pt-4">
              <Link to="/courses" className="flex items-center justify-between font-bold text-primary hover:underline">
                <span>Xem các khoá học TOEIC</span>
                <span>→</span>
              </Link>
            </div>
          </div>

          {/* IELTS TRACK */}
          <div className="group flex flex-col justify-between rounded-2xl border border-line bg-surface p-6 sm:p-8 shadow-card transition-all hover:border-primary hover:shadow-hover">
            <div className="flex flex-col gap-4">
              <div className="flex items-center justify-between">
                <Badge className="bg-tip-bg text-tip font-bold px-3 py-1">IELTS Academic & General</Badge>
                <span className="text-xs font-semibold text-muted">Mục tiêu 6.5 - 8.0+</span>
              </div>
              <h3 className="font-sans text-xl font-bold text-ink group-hover:text-primary transition-colors">Bứt Phá 4 Kỹ Năng IELTS Toàn Diện</h3>
              <p className="text-sm text-muted leading-relaxed">
                Rèn luyện tư duy phản xạ Speaking, kỹ năng lập luận Writing Task 1 & Task 2 mạch lạc theo chuẩn PEEL,
                và giải đề Reading & Listening chuyên sâu.
              </p>
              <ul className="flex flex-col gap-2.5 text-xs text-ink pt-2">
                <li className="flex items-center gap-2">
                  <span className="text-success font-bold text-sm">✔</span> AI Grader chấm Writing Task 1 & 2 chi tiết từng tiêu chí
                </li>
                <li className="flex items-center gap-2">
                  <span className="text-success font-bold text-sm">✔</span> Thu âm Speaking tương tác trực tiếp trên trình duyệt
                </li>
                <li className="flex items-center gap-2">
                  <span className="text-success font-bold text-sm">✔</span> Đầy đủ dạng bài matching headings, diagram, essay...
                </li>
              </ul>
            </div>
            <div className="mt-6 border-t border-line pt-4">
              <Link to="/courses" className="flex items-center justify-between font-bold text-primary hover:underline">
                <span>Xem các khoá học IELTS</span>
                <span>→</span>
              </Link>
            </div>
          </div>
        </div>
      </section>

      {/* 4. REAL EXAM ROOM EXPERIENCE HIGHLIGHT */}
      <section className="rounded-2xl border border-line bg-surface p-8 shadow-card sm:p-10">
        <div className="grid gap-8 lg:grid-cols-2 lg:items-center">
          <div>
            <Badge className="bg-success-bg text-success font-bold">PHÒNG THI CHUẨN QUỐC TẾ</Badge>
            <h2 className="mt-3 font-sans text-2xl font-black text-ink sm:text-3xl tracking-tight">
              Mô Phỏng 100% Áp Lực Phòng Thi Thật
            </h2>
            <p className="mt-4 text-sm leading-relaxed text-muted sm:text-base">
              Không chỉ là làm bài trắc nghiệm thông thường, phòng thi của English9x được xây dựng với đầy đủ quy chế
              và công cụ hỗ trợ như kỳ thi thật tại các trung tâm khảo thí quốc tế:
            </p>
            <div className="mt-6 flex flex-col gap-3.5 text-sm text-ink">
              <div className="flex items-start gap-3">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-info-bg text-info font-bold text-xs">1</span>
                <div>
                  <strong>Đồng hồ đếm ngược từng phần (Section Timer):</strong> Báo hiệu chuyển phần thi tự động, rèn luyện kỹ năng phân bổ thời gian.
                </div>
              </div>
              <div className="flex items-start gap-3">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-info-bg text-info font-bold text-xs">2</span>
                <div>
                  <strong>Phát Audio 1 lần duy nhất:</strong> Rèn luyện sự tập trung tối đa, mô phỏng đúng quy chế thi Listening quốc tế.
                </div>
              </div>
              <div className="flex items-start gap-3">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-info-bg text-info font-bold text-xs">3</span>
                <div>
                  <strong>Cơ chế Tự động lưu đáp án (Autosave):</strong> Không lo mất bài khi gặp sự cố mạng, dữ liệu luôn được đồng bộ tức thì.
                </div>
              </div>
            </div>
          </div>

          {/* Exam room preview mock card */}
          <div className="rounded-xl border border-line bg-paper p-6 shadow-card">
            <div className="flex items-center justify-between border-b border-line pb-3">
              <div className="flex items-center gap-2">
                <span className="h-3 w-3 rounded-full bg-danger animate-pulse" />
                <span className="font-mono text-sm font-bold text-ink">01:45:20</span>
              </div>
              <Badge>TOEIC FULL TEST 2026</Badge>
            </div>
            <div className="mt-4 flex flex-col gap-3">
              <p className="font-sans text-sm font-bold text-ink">
                Question 101. Customer satisfaction is our highest _______ this year.
              </p>
              <div className="flex flex-col gap-2 text-xs">
                <div className="rounded border border-primary bg-surface p-2 font-medium text-primary">
                  ● A. priority (Đã chọn)
                </div>
                <div className="rounded border border-line bg-surface p-2 text-muted">
                  ○ B. prioritize
                </div>
                <div className="rounded border border-line bg-surface p-2 text-muted">
                  ○ C. prioritized
                </div>
                <div className="rounded border border-line bg-surface p-2 text-muted">
                  ○ D. prioritizing
                </div>
              </div>
              <div className="mt-2 flex items-center justify-between text-xs text-muted">
                <span className="text-success font-medium">✔ Đã lưu bài tự động</span>
                <span className="font-mono">Part 5 • Câu 101/200</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 5. CALL TO ACTION BANNER */}
      <section className="rounded-2xl border border-primary bg-primary p-8 text-center text-on-primary shadow-lg sm:p-12">
        <h2 className="font-sans text-3xl font-black sm:text-4xl tracking-tight">
          Sẵn Sàng Bứt Phá Điểm Số Của Bạn?
        </h2>
        <p className="mx-auto mt-4 max-w-xl text-sm opacity-90 sm:text-base">
          Tham gia cùng hàng nghìn học viên đang ôn luyện mỗi ngày trên English9x. Trải nghiệm kho đề thi phong phú
          và nhận kết quả đánh giá năng lực chi tiết ngay hôm nay.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-4">
          <Link to="/courses">
            <Button size="lg" className="border-none bg-surface text-ink hover:bg-paper px-8 font-bold shadow-md">
              Bắt đầu luyện tập ngay
            </Button>
          </Link>
          <Link to="/register">
            <Button variant="secondary" size="lg" className="border-on-primary text-on-primary hover:bg-primary-hover px-8 font-bold">
              Đăng ký tài khoản miễn phí
            </Button>
          </Link>
        </div>
      </section>
    </div>
  );
}
