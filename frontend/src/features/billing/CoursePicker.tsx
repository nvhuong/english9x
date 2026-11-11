import { useTranslation } from 'react-i18next';

export interface PickableCourse {
  id: string;
  code: string;
  title: string;
  examCode: string;
}

/** Courses to put in the order. Once `max` are ticked the others are disabled: an order never holds more than the plan allows. */
export function CoursePicker({ courses, max, value, onChange }: { courses: PickableCourse[]; max: number; value: string[]; onChange: (ids: string[]) => void }) {
  const { t } = useTranslation('billing');
  const full = value.length >= max;
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((x) => x !== id) : value.length >= max ? value : [...value, id]);
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="font-serif text-xl font-semibold text-ink">{t('checkout.courseLegend')}</legend>
      <p role="status" className="text-sm text-muted" data-testid="course-count">
        {t('checkout.courseCount', { count: value.length, max })}
      </p>
      {courses.map((c) => {
        const checked = value.includes(c.id);
        return (
          <label key={c.id} className="flex items-center gap-3 rounded-md border border-line px-3 py-2">
            <input type="checkbox" checked={checked} disabled={full && !checked} onChange={() => toggle(c.id)} />
            <span>
              <span className="font-semibold text-ink">{c.title}</span> <span className="text-sm text-muted">{`${c.examCode} · ${c.code}`}</span>
            </span>
          </label>
        );
      })}
    </fieldset>
  );
}
