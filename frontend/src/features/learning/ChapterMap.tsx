import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge } from '../../design';
import type { ChapterMapData, MapLessonData } from './types';

function lessonPath(l: MapLessonData): string {
  return l.kind === 'CHAPTER_TEST' ? `/learn/chapter-tests/${l.id}` : `/learn/lessons/${l.id}`;
}

function percent(score: number | null | undefined): string | null {
  return score === null || score === undefined ? null : `${Math.round(score * 100)}%`;
}

/** The map of a course for its learner (F22): chapters, lessons and chapter tests with their state, and the day's study. Locked ones are named, not linked. */
export function ChapterMap({ map }: { map: ChapterMapData }) {
  const { t } = useTranslation('learning');
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="font-serif text-3xl font-bold text-ink">{map.title}</h1>
        <p className="text-sm text-muted">{t(`map.rule.${map.unlockRule}`)}</p>
        <p className="flex flex-wrap gap-4 text-sm text-ink">
          <span data-testid="streak">{t('map.streak', { count: map.streakDays })}</span>
          <span data-testid="today">{t('map.today', { minutes: map.todayMinutes })}</span>
        </p>
      </header>
      <ol className="flex flex-col gap-4">
        {map.chapters.map((c, i) => (
          <li key={i} className="rounded-md border border-edge p-4">
            <h2 className="font-semibold text-ink">{t('map.chapterN', { n: i + 1, title: c.title })}</h2>
            <ul className="mt-2 flex flex-col gap-2">
              {c.lessons.map((l) => (
                <li key={l.id} className="flex flex-wrap items-center gap-2 text-ink">
                  {l.locked ? (
                    <span aria-disabled="true" className="text-muted">
                      {l.title}
                    </span>
                  ) : (
                    <Link to={lessonPath(l)} className="text-primary underline">
                      {l.title}
                    </Link>
                  )}
                  <Badge>{t(`map.kind.${l.kind}`)}</Badge>
                  {l.locked && <span className="text-sm text-muted">{t('map.locked')}</span>}
                  {l.completed && <span className="text-sm font-medium text-success">{t('map.done')}</span>}
                  {percent(l.bestScore) && <span className="text-sm text-muted">{t('map.best', { score: percent(l.bestScore) })}</span>}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ol>
    </div>
  );
}
