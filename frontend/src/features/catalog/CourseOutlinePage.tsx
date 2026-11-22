import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { Badge, EmptyState } from '../../design';
import { useCourseOutline } from './queries';

/** /courses/:code: the outline of a course (chapters and lesson titles, author), never a lesson's content. */
export default function CourseOutlinePage() {
  const { t } = useTranslation('catalog');
  const { code } = useParams();
  const course = useCourseOutline(code);

  const totalLessons = useMemo(() => {
    if (!course.data?.chapters) return 0;
    return course.data.chapters.reduce((acc, ch) => acc + (ch.lessons?.length || 0), 0);
  }, [course.data]);

  if (course.isPending) {
    return (
      <div role="status" className="flex flex-col gap-6 py-8">
        <p className="text-muted">{t('loading')}</p>
        <div className="h-48 rounded-2xl border border-line bg-surface animate-pulse" />
        <div className="h-64 rounded-2xl border border-line bg-surface animate-pulse" />
      </div>
    );
  }

  const c = course.data;
  if (course.isError || !c) {
    const notFound = course.error instanceof ApiProblemError && course.error.status === 404;
    return (
      <EmptyState
        title={t(notFound ? 'outline.notFoundTitle' : 'errors.generic')}
        description={notFound ? t('outline.notFound') : undefined}
        action={<Link to="/courses" className="text-primary underline">{t('outline.back')}</Link>}
      />
    );
  }

  const isToeic = c.examCode === 'TOEIC';

  return (
    <div className="flex flex-col gap-8 pb-16">
      {/* 1. COURSE HERO CARD */}
      <header className="relative overflow-hidden rounded-2xl border border-line bg-surface p-6 sm:p-10 shadow-card">
        <div className="flex flex-col gap-4">
          <div>
            <Link
              to="/courses"
              className="inline-flex items-center gap-1.5 text-sm font-semibold text-primary hover:underline"
            >
              <span>←</span>
              <span>{t('outline.back')}</span>
            </Link>
          </div>

          <div className="flex flex-wrap items-center gap-2">
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
            <Badge className="bg-sunken text-muted">
              {c.unlockRule === 'FREE' ? '🔓 Tự do học' : c.unlockRule === 'COMPLETION' ? '⚡ Mở tuần tự' : '🔒 Nghiêm ngặt'}
            </Badge>
          </div>

          <h1 className="font-serif text-3xl font-bold tracking-tight text-ink sm:text-4xl">
            {c.title}
          </h1>

          {c.authorDisplay && (
            <p className="flex items-center gap-2 text-sm font-medium text-muted">
              <span>👤</span>
              <span>{t('list.by', { author: c.authorDisplay })}</span>
            </p>
          )}

          {c.description && (
            <p className="mt-1 max-w-3xl text-base leading-relaxed text-ink">
              {c.description}
            </p>
          )}

          {/* Quick Metrics Bar */}
          <div className="mt-4 flex flex-wrap items-center gap-6 border-t border-line pt-4 text-xs font-medium text-muted">
            <span className="flex items-center gap-1.5">
              <span>📚</span>
              <span>{c.chapters.length} chương</span>
            </span>
            <span className="flex items-center gap-1.5">
              <span>📝</span>
              <span>{totalLessons} bài giảng & kiểm tra</span>
            </span>
            <span className="flex items-center gap-1.5">
              <span>⚙️</span>
              <span>{t(`rule.${c.unlockRule}`)}</span>
            </span>
          </div>
        </div>
      </header>

      {/* 2. SYLLABUS / CHAPTERS TIMELINE */}
      <section aria-labelledby="outline-h" className="flex flex-col gap-6">
        <div className="flex items-center justify-between border-b border-line pb-3">
          <div>
            <h2 id="outline-h" className="font-serif text-2xl font-bold text-ink">
              {t('outline.heading')}
            </h2>
            <p className="mt-0.5 text-xs text-muted">Toàn bộ cấu trúc lộ trình và nội dung bài học</p>
          </div>
          <span className="text-xs font-semibold text-muted">
            {c.chapters.length} Phần • {totalLessons} Bài học
          </span>
        </div>

        <ol className="flex flex-col gap-6">
          {c.chapters.map((ch, i) => (
            <li
              key={i}
              className="rounded-2xl border border-line bg-surface p-6 shadow-card transition-all"
            >
              {/* Chapter title header */}
              <div className="flex items-center justify-between border-b border-line pb-4">
                <div className="flex items-center gap-3">
                  <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-xs font-bold text-on-primary">
                    {String(i + 1).padStart(2, '0')}
                  </span>
                  <h3 className="font-serif text-lg font-bold text-ink sm:text-xl">
                    {t('outline.chapterN', { n: i + 1, title: ch.title })}
                  </h3>
                </div>
                <span className="rounded-full bg-sunken px-2.5 py-0.5 text-xs font-medium text-muted">
                  {ch.lessons.length} bài
                </span>
              </div>

              {/* Lesson items list */}
              <ul className="mt-4 flex flex-col divide-y divide-line">
                {ch.lessons.map((l, lessonIdx) => {
                  const isTest = l.kind === 'CHAPTER_TEST' || l.kind === 'MOCK_TEST';
                  return (
                    <li
                      key={l.id}
                      className="flex items-center justify-between py-3 px-2 text-ink hover:bg-sunken/40 rounded-lg transition-colors"
                    >
                      <div className="flex items-center gap-3">
                        <span className="text-sm text-muted font-mono w-5 text-right">
                          {lessonIdx + 1}.
                        </span>
                        <span className="text-base">{isTest ? '📝' : '📖'}</span>
                        <span className="font-medium">{l.title}</span>
                      </div>
                      <div className="flex items-center gap-3">
                        <Badge
                          className={
                            l.kind === 'MOCK_TEST'
                              ? 'bg-info-bg text-info font-semibold'
                              : l.kind === 'CHAPTER_TEST'
                              ? 'bg-tip-bg text-tip font-semibold'
                              : 'bg-sunken text-muted'
                          }
                        >
                          {t(`kind.${l.kind}`)}
                        </Badge>
                        <span className="text-xs text-muted" title="Nội dung khoá học">
                          🔒
                        </span>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </li>
          ))}
        </ol>
      </section>

      {/* 3. CALL TO ACTION ENROLLMENT CARD */}
      <section className="rounded-2xl border border-line bg-sunken p-6 sm:p-8 text-center">
        <h3 className="font-serif text-xl font-bold text-ink sm:text-2xl">
          Đăng ký để mở khoá toàn bộ bài học & đề thi thử
        </h3>
        <p className="mx-auto mt-2 max-w-lg text-sm text-muted">
          Luyện tập đầy đủ hệ thống câu hỏi, làm bài kiểm tra từng chương và nhận đánh giá năng lực chi tiết từ AI Grader.
        </p>
        <div className="mt-6 flex flex-wrap justify-center gap-3">
          <Link
            to="/login"
            className="rounded-lg bg-primary px-6 py-2.5 text-sm font-bold text-on-primary hover:bg-primary-hover shadow-sm transition-colors"
          >
            Đăng nhập để học
          </Link>
          <Link
            to="/register"
            className="rounded-lg border border-edge bg-surface px-6 py-2.5 text-sm font-bold text-ink hover:bg-paper transition-colors"
          >
            Tạo tài khoản mới
          </Link>
        </div>
      </section>
    </div>
  );
}
