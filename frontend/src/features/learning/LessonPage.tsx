import { useQueries } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { useApi } from '../../api/useApi';
import { ctxKey, useContextStore } from '../../app/stores/context';
import { Button, EmptyState } from '../../design';
import type { Block } from '../cms/lesson-editor/types';
import { PracticeScore, usePractice } from './PracticeBlock';
import { StrategyLessonView, type EmbeddedQuestionData, type StoredFileKind } from './StrategyLessonView';
import { sendHeartbeat, useCompleteLesson, useHeartbeatIntervalMs, useLesson } from './queries';
import { useStudyHeartbeat } from './useStudyHeartbeat';

function mediaIds(blocks: Block[]): { kind: StoredFileKind; id: string }[] {
  const out: { kind: StoredFileKind; id: string }[] = [];
  for (const b of blocks) {
    if (b.type === 'image') out.push({ kind: 'IMAGE', id: b.imageMediaId });
    if (b.type === 'audio') out.push({ kind: 'AUDIO', id: b.audioMediaId });
    if (b.type === 'video-upload') out.push({ kind: 'VIDEO', id: b.videoMediaId });
  }
  return out;
}

/** /learn/lessons/:lessonId (F22): the lesson, practice with instant feedback, finish, and the study clock. */
export default function LessonPage() {
  const { t } = useTranslation('learning');
  const { lessonId = '' } = useParams();
  const api = useApi();
  const lesson = useLesson(lessonId);
  const complete = useCompleteLesson(lessonId);
  const { checkAnswer, tally } = usePractice(lessonId);
  const orgId = useContextStore((st) => st.orgId);
  const files = mediaIds(lesson.data?.blocks ?? []);
  const links = useQueries({
    queries: files.map((f) => ({
      queryKey: ctxKey(orgId, 'media-link', f.id),
      staleTime: 60_000,
      retry: false,
      queryFn: async () => ((await api.GET('/api/media/{id}/url', { params: { path: { id: f.id } } })).data as unknown as { url: string }).url,
    })),
  });
  const courseId = lesson.data?.courseId;
  const intervalMs = useHeartbeatIntervalMs();
  useStudyHeartbeat({ enabled: Boolean(courseId), intervalMs, send: () => sendHeartbeat(api, courseId!, lessonId) });

  if (lesson.isPending) return <p role="status" className="text-muted">{t('loading')}</p>;
  if (lesson.isError || !lesson.data) {
    const status = lesson.error instanceof ApiProblemError ? lesson.error.status : 0;
    return <EmptyState title={t(status === 403 ? 'errors.notEntitled' : status === 409 ? 'errors.locked' : 'errors.notFound')} action={<Link to="/learn" className="text-primary underline">{t('back')}</Link>} />;
  }
  const l = lesson.data;
  const urls = new Map(files.map((f, i) => [`${f.kind}:${f.id}`, links[i]?.data]));
  const questions: Record<string, EmbeddedQuestionData | undefined> = {};
  for (const q of l.questions) questions[q.versionId] = { versionId: q.versionId, typeCode: q.typeCode, content: q.content };
  return (
    <div className="flex flex-col gap-4">
      <Link to={`/learn/courses/${l.courseId}`} className="text-sm text-primary underline">
        {t('back')}
      </Link>
      <StrategyLessonView title={l.title} blocks={l.blocks} questionTypeCodes={l.questionTypeCodes} mediaSrc={(kind, id) => urls.get(`${kind}:${id}`)} questions={questions} checkAnswer={checkAnswer} />
      <PracticeScore tally={tally} total={l.questions.length} />
      <div className="flex items-center gap-3">
        {l.completed || complete.isSuccess ? (
          <p role="status" className="font-medium text-success">{t('lesson.completed')}</p>
        ) : (
          <Button loading={complete.isPending} onClick={() => complete.mutate()}>
            {t('lesson.complete')}
          </Button>
        )}
        {complete.isError && <p role="alert" className="text-sm text-danger">{t('errors.generic')}</p>}
        <Link to={`/learn/courses/${l.courseId}`} className="text-primary underline">
          {t('lesson.toMap')}
        </Link>
      </div>
    </div>
  );
}
