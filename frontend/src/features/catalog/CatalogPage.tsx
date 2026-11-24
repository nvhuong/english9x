import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { EmptyState, Select } from '../../design';
import { useCatalog } from './queries';

const ALL = 'all';

/** /courses: what the catalog offers, for anybody. Outlines only. */
export default function CatalogPage() {
  const { t } = useTranslation('catalog');
  const [exam, setExam] = useState(ALL);
  const [searchQuery, setSearchQuery] = useState('');
  const list = useCatalog(exam === ALL ? undefined : exam);

  const filteredCourses = useMemo(() => {
    if (!list.data) return [];
    if (!searchQuery.trim()) return list.data;
    const q = searchQuery.toLowerCase();
    return list.data.filter(
      (c) =>
        c.title.toLowerCase().includes(q) ||
        c.code.toLowerCase().includes(q) ||
        (c.description && c.description.toLowerCase().includes(q)) ||
        (c.authorDisplay && c.authorDisplay.toLowerCase().includes(q))
    );
  }, [list.data, searchQuery]);

  return (
    <div className="flex flex-col gap-8 pb-12">
      {/* 1. HERO HEADER */}
      <header className="relative overflow-hidden rounded-2xl border border-line bg-surface p-6 sm:p-10 shadow-card">
        <div className="max-w-3xl">
          <div className="inline-flex items-center gap-2 rounded-full border border-line bg-sunken px-3.5 py-1 text-xs font-semibold text-primary">
            <span>📚 GIÁO TRÌNH & ĐỀ THI CHÍNH THỨC</span>
          </div>
          <h1 className="mt-4 font-sans text-3xl font-black tracking-tight text-ink sm:text-4xl">
            {t('list.title')}
          </h1>
          <p className="mt-2 text-base leading-relaxed text-muted sm:text-lg">
            {t('list.description')}
          </p>
        </div>

        {/* Filters and Search Bar */}
        <div className="mt-8 flex flex-col gap-4 border-t border-line pt-6 sm:flex-row sm:items-end sm:justify-between">
          <div className="flex flex-wrap items-center gap-3">
            <Select
              label={t('list.exam')}
              value={exam}
              onValueChange={setExam}
              options={[
                { value: ALL, label: t('list.all') },
                { value: 'IELTS', label: 'IELTS' },
                { value: 'TOEIC', label: 'TOEIC' },
              ]}
              className="min-w-44 max-w-xs"
            />

            {/* Quick Filter Pills */}
            <div className="flex items-center gap-1.5 pt-5">
              {[
                { id: ALL, label: 'Tất cả' },
                { id: 'TOEIC', label: '🎯 TOEIC' },
                { id: 'IELTS', label: '🎓 IELTS' },
              ].map((pill) => (
                <button
                  key={pill.id}
                  type="button"
                  onClick={() => setExam(pill.id)}
                  className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors ${
                    exam === pill.id
                      ? 'bg-primary text-on-primary'
                      : 'bg-sunken text-muted hover:bg-line hover:text-ink'
                  }`}
                >
                  {pill.label}
                </button>
              ))}
            </div>
          </div>

          {/* Search Input */}
          <div className="w-full sm:w-72">
            <label htmlFor="course-search" className="block text-xs font-semibold text-muted mb-1.5">
              Tìm kiếm khoá học
            </label>
            <div className="relative">
              <input
                id="course-search"
                type="search"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Nhập tên khoá, mã khoá..."
                className="w-full rounded-md border border-edge bg-surface px-3 py-2 text-sm text-ink placeholder:text-muted focus:border-focus focus:outline-none focus:ring-1 focus:ring-focus"
              />
            </div>
          </div>
        </div>
      </header>

      {/* 2. COURSE LIST / STATES */}
      {list.isPending ? (
        <div role="status" className="flex flex-col gap-4">
          <p className="text-muted">{t('loading')}</p>
          <div className="grid gap-6 sm:grid-cols-2">
            {[1, 2].map((n) => (
              <div key={n} className="h-64 rounded-2xl border border-line bg-surface p-6 animate-pulse" />
            ))}
          </div>
        </div>
      ) : list.isError ? (
        <EmptyState title={t('errors.generic')} />
      ) : list.data.length === 0 ? (
        <EmptyState title={t('list.emptyTitle')} description={t('list.empty')} />
      ) : filteredCourses.length === 0 ? (
        <EmptyState
          title="Không tìm thấy kết quả phù hợp"
          description={`Không có khoá học nào khớp với từ khoá "${searchQuery}".`}
          action={
            <button
              type="button"
              onClick={() => setSearchQuery('')}
              className="text-sm font-semibold text-primary underline"
            >
              Xoá bộ lọc tìm kiếm
            </button>
          }
        />
      ) : (
        <div className="flex flex-col gap-4">
          <div className="flex items-center justify-between text-xs font-medium text-muted px-1">
            <span>Hiển thị {filteredCourses.length} khoá học tiêu chuẩn</span>
            <span>Cập nhật mới nhất 2026</span>
          </div>

          <ul className="grid gap-6 sm:grid-cols-2" aria-label={t('list.caption')}>
            {filteredCourses.map((c) => {
              const isToeic = c.examCode === 'TOEIC';
              return (
                <li
                  key={c.code}
                  className="group relative flex flex-col justify-between rounded-2xl border border-line bg-surface p-6 shadow-card hover:border-primary hover:shadow-hover transition-all duration-200"
                >
                  <div className="flex flex-col gap-3">
                    {/* Header line: Exam badge + Code pill */}
                    <div className="flex items-center justify-between gap-2">
                      <span
                        className={`inline-flex items-center gap-1.5 rounded-full px-3 py-0.5 text-xs font-bold border ${
                          isToeic
                            ? 'bg-info-bg text-info border-line'
                            : 'bg-tip-bg text-tip border-line'
                        }`}
                      >
                        {isToeic ? '🎯' : '🎓'} {c.examCode}
                      </span>
                      <span className="font-mono text-xs font-semibold px-2 py-0.5 rounded bg-sunken text-muted border border-line">
                        {c.code}
                      </span>
                    </div>

                    {/* Course Title */}
                    <h2 className="font-sans text-2xl font-bold tracking-tight text-ink group-hover:text-primary transition-colors">
                      <Link to={`/courses/${c.code}`} className="hover:underline">
                        {c.title}
                      </Link>
                    </h2>

                    {/* Description */}
                    {c.description && (
                      <p className="text-sm text-ink leading-relaxed line-clamp-3">
                        {c.description}
                      </p>
                    )}

                    {/* Metadata & Counts */}
                    <div className="mt-2 flex flex-wrap items-center gap-4 text-xs text-muted border-t border-line pt-3">
                      <span className="flex items-center gap-1.5 font-medium">
                        <span>📖</span>
                        <span>{t('list.counts', { chapters: c.chapters, lessons: c.lessons })}</span>
                      </span>
                      {c.authorDisplay && (
                        <span className="flex items-center gap-1.5 font-medium">
                          <span>👤</span>
                          <span>{t('list.by', { author: c.authorDisplay })}</span>
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Card Footer CTA Button */}
                  <div className="mt-6 border-t border-line pt-4">
                    <Link
                      to={`/courses/${c.code}`}
                      className="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-sunken px-4 py-2.5 text-sm font-semibold text-primary border border-line hover:bg-primary hover:text-on-primary transition-colors"
                    >
                      <span>Xem giáo trình chi tiết</span>
                      <span>→</span>
                    </Link>
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
