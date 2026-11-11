import { useTranslation } from 'react-i18next';
import { cn } from '../../design';
import type { SaveState } from './useAnswerSync';

/** "Saved / saving / offline (n waiting)": text, not colour alone. */
export function SaveStatus({ state, pending }: { state: SaveState; pending: number }) {
  const { t } = useTranslation('exam');
  return (
    <p role="status" data-state={state} className={cn('text-sm font-semibold', state === 'offline' || state === 'rejected' ? 'text-danger' : 'text-muted')}>
      {t(`save.${state}`, { count: pending })}
    </p>
  );
}
