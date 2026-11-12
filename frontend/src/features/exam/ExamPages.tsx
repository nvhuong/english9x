import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button, EmptyState } from '../../design';
import { ExamRoom } from './ExamRoom';
import { ExamHttpError, examApi } from './examApi';
import type { ExamAttemptView } from './types';
import { markFresh, useExamAttempt } from './useExamAttempt';

/** /exam/start/:testVersionId: starts (or continues) the attempt and goes to its room. */
export function StartExamPage() {
  const { testVersionId = '' } = useParams();
  const { t } = useTranslation('exam');
  const navigate = useNavigate();
  const [error, setError] = useState<number | null>(null);
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    examApi.start(testVersionId).then(
      (v) => {
        // 201 and 409 (already running) both answer with the attempt; only the page load that really started it skips the resume
        markFresh(v.attemptId);
        navigate(`/exam/attempts/${v.attemptId}`, { replace: true });
      },
      (e: unknown) => setError(e instanceof ExamHttpError ? e.status : 0),
    );
  }, [testVersionId, navigate]);
  if (error !== null) {
    return (
      <div className="mx-auto max-w-xl p-6">
        <EmptyState title={error === 403 ? t('start.notEntitled') : error === 404 ? t('start.notFound') : t('start.failed')} description={error === 403 ? t('start.notEntitledHelp') : undefined} />
        <Link to="/" className="mt-4 inline-block text-primary underline">{t('result.home')}</Link>
      </div>
    );
  }
  return <p role="status" className="p-6 text-muted">{t('start.starting')}</p>;
}

function Result({ view }: { view: ExamAttemptView }) {
  const { t } = useTranslation('exam');
  const r = view.result;
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4 p-6">
      <h1 className="font-serif text-3xl font-bold text-ink">{r?.interrupted ? t('result.interruptedTitle') : t('result.title')}</h1>
      <p data-testid="exam-result" data-status={view.status} data-interrupted={String(Boolean(r?.interrupted))} className="text-ink">
        {r?.interrupted ? t('result.interrupted') : t('result.submitted')}
      </p>
      <p className="text-muted">{t('result.scoreLater')}</p>
      <Button asChild><Link to={`/results/attempts/${view.attemptId}`}>{t('result.viewResults')}</Link></Button>
      <Button asChild variant="secondary"><Link to="/">{t('result.home')}</Link></Button>
    </div>
  );
}

/** /exam/attempts/:attemptId */
export function ExamAttemptPage() {
  const { attemptId = '' } = useParams();
  const { t } = useTranslation('exam');
  const { load, setView, refresh } = useExamAttempt(attemptId);
  if (load.status === 'loading') return <p role="status" className="p-6 text-muted">{t('loading')}</p>;
  if (load.status === 'error') return <div className="mx-auto max-w-xl p-6"><EmptyState title={load.code === 404 ? t('start.notFound') : t('start.failed')} /></div>;
  const view = load.view;
  if (view.status === 'SUBMITTED' || !view.currentSection) return <Result view={view} />;
  return <ExamRoom key={`${view.attemptId}:${view.currentSection.sectionId}`} view={view} onView={setView} onRefresh={() => void refresh()} />;
}

export default ExamAttemptPage;
