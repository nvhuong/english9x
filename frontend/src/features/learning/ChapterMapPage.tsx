import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { EmptyState } from '../../design';
import { ChapterMap } from './ChapterMap';
import { useChapterMap } from './queries';

/** /learn/courses/:courseId: the map of a course the person has a plan for. */
export default function ChapterMapPage() {
  const { t } = useTranslation('learning');
  const { courseId } = useParams();
  const map = useChapterMap(courseId);
  if (map.isPending) return <p role="status" className="text-muted">{t('loading')}</p>;
  if (map.isError || !map.data) {
    const status = map.error instanceof ApiProblemError ? map.error.status : 0;
    return <EmptyState title={t(status === 403 ? 'errors.notEntitled' : 'errors.notFound')} action={<Link to="/learn" className="text-primary underline">{t('back')}</Link>} />;
  }
  return <ChapterMap map={map.data} />;
}
