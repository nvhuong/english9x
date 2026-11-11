import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useApi } from '../../api/useApi';
import { ctxKey, useContextStore } from '../../app/stores/context';
import { EmptyState } from '../../design';

interface Plan {
  id: string;
  courseId: string;
  courseCode?: string | null;
  active: boolean;
}

/** /learn: the courses the person has a plan for, each leading to its map. */
export default function MyLearningPage() {
  const { t } = useTranslation('learning');
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  const plans = useQuery({
    queryKey: ctxKey(ctx, 'learning', 'plans'),
    queryFn: async () => ((await api.GET('/api/me/entitlements')).data ?? []) as unknown as Plan[],
  });
  if (plans.isPending) return <p role="status" className="text-muted">{t('loading')}</p>;
  const active = (plans.data ?? []).filter((p) => p.active);
  if (active.length === 0) return <EmptyState title={t('mine.empty')} action={<Link to="/courses" className="text-primary underline">{t('mine.browse')}</Link>} />;
  return (
    <div className="flex flex-col gap-3">
      <h1 className="font-serif text-3xl font-bold text-ink">{t('mine.title')}</h1>
      <ul className="flex flex-col gap-2">
        {active.map((p) => (
          <li key={p.id}>
            <Link to={`/learn/courses/${p.courseId}`} className="text-primary underline">
              {p.courseCode ?? p.courseId}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
