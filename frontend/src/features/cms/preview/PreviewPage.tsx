import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../../api/problem';
import { Badge, EmptyState } from '../../../design';
import { kindOf } from '../question-form/kinds';
import { useQuestion, useQuestionTypeForms } from '../questions/queries';
import { QuestionPreview } from './QuestionPreview';

/**
 * /cms/questions/:id/preview: "Xem trước như học viên". The author or reviewer sees the question the way the exam shows it. The page hands the preview the
 * content and nothing else: the answer key, the explanation and the transcript stay on the editor page, the solution side.  The question is drawn by the exam room's own
 * component (QuestionInput).
 */
export default function PreviewPage() {
  const { t } = useTranslation('preview');
  const { id } = useParams();
  const question = useQuestion(id);
  const types = useQuestionTypeForms();
  const q = question.data;

  const back = (
    <Link to={id ? `/cms/questions/${id}` : '/cms/questions'} className="text-sm text-primary underline">
      {t('back')}
    </Link>
  );
  if (question.isPending || types.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  if (question.isError || !q) {
    const missing = question.error instanceof ApiProblemError && (question.error.status === 404 || question.error.status === 403);
    return (
      <div className="flex flex-col gap-3">
        {back}
        <EmptyState title={t(missing ? 'notFoundTitle' : 'generic')} description={missing ? t('notFound') : undefined} />
      </div>
    );
  }
  const type = types.data?.find((x) => x.code === q.typeCode);
  const kind = type ? kindOf(type) : undefined;
  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-col gap-2">
        {back}
        <h1 className="font-sans text-2xl sm:text-3xl font-black tracking-tight text-ink">{t('title')}</h1>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
          <Badge>{t(`status.${q.status}`)}</Badge>
          <span>{t('meta', { n: q.versionNo })}</span>
          <code>{q.typeCode}</code>
        </p>
        <p className="max-w-prose text-muted">{t('description')}</p>
      </header>
      {kind ? <QuestionPreview kind={kind} content={q.content} /> : <EmptyState title={t('noKindTitle')} description={t('noKind')} />}
    </div>
  );
}
