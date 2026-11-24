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
    <div className="flex flex-col gap-6">
      <div className="rounded-2xl border border-line bg-surface p-6 shadow-card sm:p-8">
        <h1 className="font-sans text-3xl font-black tracking-tight text-ink sm:text-4xl">{t('mine.title')}</h1>
        <p className="mt-2 text-sm text-muted">Các khoá học và chương trình luyện thi đang hoạt động của bạn.</p>
      </div>
      <ul className="grid gap-4 sm:grid-cols-2">
        {active.map((p) => (
          <li key={p.id} className="rounded-2xl border border-line bg-surface p-6 shadow-card hover:border-primary transition-all">
            <Link to={`/learn/courses/${p.courseId}`} className="font-sans text-lg font-bold text-primary hover:underline">
              {p.courseCode ?? p.courseId} →
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
