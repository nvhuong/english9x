import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input, Select } from '../../design';
import { actionLabel } from './auditText';
import { NO_FILTERS, type AuditFilters } from './queries';

const ALL = '__all__';

/** The filters. Nothing is asked of the server until "Apply" (or Enter) is pressed. */
export function AuditFilterForm({ value, actions, onApply }: { value: AuditFilters; actions: readonly string[]; onApply: (filters: AuditFilters) => void }) {
  const { t } = useTranslation('audit');
  const [draft, setDraft] = useState<AuditFilters>(value);
  // when the applied filters change from outside (the empty result's "clear filters"), the fields follow
  const [seen, setSeen] = useState(value);
  if (seen !== value) {
    setSeen(value);
    setDraft(value);
  }
  const set = (patch: Partial<AuditFilters>) => setDraft((d) => ({ ...d, ...patch }));
  const options = [
    { value: ALL, label: t('filters.anyAction') },
    ...actions.map((a) => ({ value: a, label: actionLabel(t, a) })),
  ];
  return (
    <form
      aria-label={t('filters.title')}
      className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
      onSubmit={(e) => {
        e.preventDefault();
        onApply(draft);
      }}
    >
      <Select label={t('filters.action')} options={options} value={draft.action || ALL} onValueChange={(v) => set({ action: v === ALL ? '' : v })} />
      <Input label={t('filters.actor')} hint={t('filters.actorHint')} autoComplete="off" value={draft.actor} onChange={(e) => set({ actor: e.target.value })} />
      <Input label={t('filters.targetType')} autoComplete="off" value={draft.targetType} onChange={(e) => set({ targetType: e.target.value })} />
      <Input label={t('filters.targetId')} autoComplete="off" value={draft.targetId} onChange={(e) => set({ targetId: e.target.value })} />
      <Input label={t('filters.from')} type="date" value={draft.from} onChange={(e) => set({ from: e.target.value })} />
      <Input label={t('filters.to')} type="date" value={draft.to} onChange={(e) => set({ to: e.target.value })} />
      <div className="flex flex-wrap items-end gap-2 sm:col-span-2 lg:col-span-3">
        <Button type="submit">{t('filters.apply')}</Button>
        <Button
          type="button"
          variant="secondary"
          onClick={() => {
            setDraft(NO_FILTERS);
            onApply(NO_FILTERS);
          }}
        >
          {t('filters.reset')}
        </Button>
      </div>
    </form>
  );
}
