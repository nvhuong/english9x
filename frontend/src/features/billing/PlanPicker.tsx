import { useTranslation } from 'react-i18next';
import type { PlanView } from './queries';

const money = (vnd: number, locale: string) => new Intl.NumberFormat(locale).format(vnd);

/** The plans on sale as a radio group: name, months, price and how many courses one order may hold. */
export function PlanPicker({ plans, value, onChange }: { plans: PlanView[]; value: string | null; onChange: (id: string) => void }) {
  const { t, i18n } = useTranslation('billing');
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="font-serif text-xl font-semibold text-ink">{t('checkout.planLegend')}</legend>
      {plans.map((p) => (
        <label key={p.id} className="flex cursor-pointer items-start gap-3 rounded-md border border-line px-3 py-2 has-[:checked]:border-primary">
          <input type="radio" name="plan" className="mt-1" value={p.id} checked={value === p.id} onChange={() => onChange(p.id)} />
          <span>
            <span className="block font-semibold text-ink">{p.name}</span>
            <span className="block text-sm text-muted">
              {t('checkout.planDetail', { months: p.months, max: p.maxCourses, price: money(p.priceVnd, i18n.language) })}
            </span>
          </span>
        </label>
      ))}
    </fieldset>
  );
}
