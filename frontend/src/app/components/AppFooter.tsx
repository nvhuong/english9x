import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { BackendStatus } from './BackendStatus';

export function AppFooter() {
  const { t } = useTranslation();
  return (
    <footer className="border-t border-line bg-surface text-sm text-muted">
      <div className="mx-auto max-w-6xl px-4 py-12">
        <div className="grid grid-cols-1 gap-8 md:grid-cols-4 lg:grid-cols-5">
          {/* Brand info */}
          <div className="flex flex-col gap-3 md:col-span-2">
            <div className="flex items-center gap-2.5 font-sans text-xl font-black text-primary">
              <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary text-xs font-black text-on-primary shadow-xs">
                9x
              </span>
              <span>{t('app.name')}</span>
            </div>
            <p className="max-w-sm text-xs leading-relaxed text-muted">
              {t('app.tagline')} — Hệ thống khảo thí và luyện thi chuẩn quốc tế ETS & Cambridge với công nghệ AI Grader 24/7 và radar điểm yếu tự động.
            </p>
            <div className="mt-2 flex items-center gap-2 text-xs text-muted">
              <span className="inline-flex items-center gap-1.5 rounded-full bg-sunken px-2.5 py-1 font-mono text-[11px] font-semibold text-primary border border-line">
                <span>🛡️</span> Chuẩn khảo thí 2026
              </span>
            </div>
          </div>

          {/* Quick links: Khoá học & Khảo thí */}
          <div className="flex flex-col gap-2.5">
            <h4 className="font-sans text-xs font-bold uppercase tracking-wider text-ink">Khoá Học & Đề Thi</h4>
            <ul className="flex flex-col gap-2 text-xs">
              <li>
                <Link to="/courses" className="hover:text-primary transition-colors">TOEIC Listening & Reading</Link>
              </li>
              <li>
                <Link to="/courses" className="hover:text-primary transition-colors">IELTS Academic 4 Kỹ Năng</Link>
              </li>
              <li>
                <Link to="/courses" className="hover:text-primary transition-colors">Ngân hàng đề thi thử ETS</Link>
              </li>
              <li>
                <Link to="/courses" className="hover:text-primary transition-colors">Luyện đề Mini-Test 2026</Link>
              </li>
            </ul>
          </div>

          {/* Quick links: Tính năng học viên */}
          <div className="flex flex-col gap-2.5">
            <h4 className="font-sans text-xs font-bold uppercase tracking-wider text-ink">Công Nghệ AI</h4>
            <ul className="flex flex-col gap-2 text-xs">
              <li>
                <Link to="/results/weakness" className="hover:text-primary transition-colors">Radar phân tích điểm yếu</Link>
              </li>
              <li>
                <Link to="/courses" className="hover:text-primary transition-colors">AI Grader chấm Writing</Link>
              </li>
              <li>
                <Link to="/courses" className="hover:text-primary transition-colors">AI Grader chấm Speaking</Link>
              </li>
              <li>
                <Link to="/learn" className="hover:text-primary transition-colors">Lộ trình học cá nhân hoá</Link>
              </li>
            </ul>
          </div>

          {/* Quick links: Hệ thống */}
          <div className="flex flex-col gap-2.5">
            <h4 className="font-sans text-xs font-bold uppercase tracking-wider text-ink">Hệ Thống</h4>
            <ul className="flex flex-col gap-2 text-xs">
              <li>
                <Link to="/login" className="hover:text-primary transition-colors">Đăng nhập tài khoản</Link>
              </li>
              <li>
                <Link to="/register" className="hover:text-primary transition-colors">Tạo tài khoản mới</Link>
              </li>
              <li>
                <Link to="/billing/plans" className="hover:text-primary transition-colors">Bảng giá gói học viên</Link>
              </li>
              <li>
                <Link to="/org" className="hover:text-primary transition-colors">Dành cho Doanh nghiệp & Trường</Link>
              </li>
            </ul>
          </div>
        </div>

        {/* Bottom divider and copyright */}
        <div className="mt-10 flex flex-wrap items-center justify-between gap-4 border-t border-line pt-6 text-xs text-muted">
          <p>© 2026 English9x Platform. Chuẩn khảo thí ETS & Cambridge. Bảo lưu mọi quyền.</p>
          <BackendStatus />
        </div>
      </div>
    </footer>
  );
}

