import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { Button, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { useCatalog } from '../catalog/queries';
import { CoursePicker } from './CoursePicker';
import { PlanPicker } from './PlanPicker';
import { useCreateOrder, usePlans } from './queries';

/** /checkout: choose a plan, then up to its limit of English9x courses, place the order and go to its page (QR, account, exact note). */
export default function CheckoutPage() {
  const { t } = useTranslation('billing');
  const navigate = useNavigate();
  const plans = usePlans();
  const catalog = useCatalog(undefined);
  const create = useCreateOrder();
  const [planId, setPlanId] = useState<string | null>(null);
  const [picked, setPicked] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const plan = plans.data?.find((p) => p.id === planId);
  // the server accepts English9x courses only (spec: an order covers published English9x courses; an organisation's shared course is not for sale), so the picker
  // does not offer them; the server still validates every id
  const courses = (catalog.data ?? []).filter((c) => !c.shared).map((c) => ({ id: c.id, code: c.code, title: c.title, examCode: c.examCode }));

  const problemText = (err: unknown) => {
    if (err instanceof ApiProblemError) {
      if (err.type === 'urn:e9x:problem:email-not-verified') return t('checkout.unverified');
      if (err.status === 401) return t('checkout.signIn');
      if (err.status === 400) return t('checkout.invalid');
    }
    return t('checkout.failed');
  };

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('checkout.title')}</h1>
        <p className="mt-2 text-muted">{t('checkout.description')}</p>
      </header>
      {(plans.isPending || catalog.isPending) && <p role="status" className="text-muted">{t('checkout.loading')}</p>}
      {(plans.isError || catalog.isError) && <FormAlert>{t('checkout.loadFailed')}</FormAlert>}
      {plans.data && plans.data.length === 0 && <EmptyState icon="🛒" title={t('checkout.noPlans')} />}
      {plans.data && plans.data.length > 0 && (
        <form
          aria-label={t('checkout.title')}
          className="flex flex-col gap-6"
          onSubmit={(e) => {
            e.preventDefault();
            if (!plan || picked.length === 0) return;
            setError(null);
            create.mutate({ planId: plan.id, courseIds: picked }, { onSuccess: (o) => navigate(`/orders/${o.orderCode}`), onError: (err) => setError(problemText(err)) });
          }}
        >
          <PlanPicker
            plans={plans.data}
            value={planId}
            onChange={(id) => {
              setPlanId(id);
              const max = plans.data.find((p) => p.id === id)?.maxCourses ?? 0;
              setPicked((cur) => cur.slice(0, max));
            }}
          />
          {plan && <CoursePicker courses={courses} max={plan.maxCourses} value={picked} onChange={setPicked} />}
          {error && <FormAlert>{error}</FormAlert>}
          <div>
            <Button type="submit" loading={create.isPending} disabled={!plan || picked.length === 0}>
              {t('checkout.submit')}
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}
