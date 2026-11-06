import { useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';

export interface NavigatorQuestion {
  id: string;
  /** Number shown to the candidate (may be "5a"). */
  number: number | string;
  answered: boolean;
  flagged?: boolean;
}

export interface QuestionNavigatorProps {
  questions: readonly NavigatorQuestion[];
  currentId: string;
  onSelect: (id: string) => void;
  /** Buttons per row, used by Up/Down. Default 10. */
  columns?: number;
  className?: string;
}

/**
 * Question grid for the exam room. One tab stop (roving tabindex): arrows/Home/End move focus, Enter/Space select.
 * State is conveyed by text in the accessible name and by shape (filled = answered, flag glyph = flagged), not colour alone.
 */
export function QuestionNavigator({ questions, currentId, onSelect, columns = 10, className }: QuestionNavigatorProps) {
  const { t } = useTranslation();
  const [focusId, setFocusId] = useState(currentId);
  const refs = useRef(new Map<string, HTMLButtonElement>());
  const active = questions.some((q) => q.id === focusId) ? focusId : currentId;

  const move = (to: number) => {
    const q = questions[Math.max(0, Math.min(questions.length - 1, to))];
    if (!q) return;
    setFocusId(q.id);
    refs.current.get(q.id)?.focus();
  };

  const onKeyDown = (e: KeyboardEvent, index: number) => {
    const target: Record<string, number> = {
      ArrowRight: index + 1,
      ArrowLeft: index - 1,
      ArrowDown: index + columns,
      ArrowUp: index - columns,
      Home: 0,
      End: questions.length - 1,
    };
    const to = target[e.key];
    if (to !== undefined) {
      e.preventDefault();
      move(to);
    }
  };

  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <div role="group" aria-label={t('navigator.label')} className="flex flex-wrap gap-1.5">
        {questions.map((q, i) => {
          const isCurrent = q.id === currentId;
          const name = [t('navigator.question', { n: q.number }), q.answered ? t('navigator.answered') : t('navigator.unanswered'), q.flagged ? t('navigator.flagged') : null].filter(Boolean).join(', ');
          return (
            <button
              key={q.id}
              ref={(el) => {
                if (el) refs.current.set(q.id, el);
                else refs.current.delete(q.id);
              }}
              type="button"
              aria-label={name}
              aria-current={isCurrent ? 'step' : undefined}
              tabIndex={q.id === active ? 0 : -1}
              onFocus={() => setFocusId(q.id)}
              onKeyDown={(e) => onKeyDown(e, i)}
              onClick={() => onSelect(q.id)}
              className={cn(
                'relative size-10 rounded-sm border-2 text-sm font-bold tabular-nums',
                q.answered ? 'border-primary bg-primary text-on-primary' : 'border-edge bg-surface text-ink',
                isCurrent && 'ring-2 ring-focus ring-offset-2 ring-offset-paper',
              )}
            >
              <span aria-hidden="true">{q.number}</span>
              {q.flagged && (
                <span aria-hidden="true" className="absolute -right-1 -top-2 text-base leading-none text-accent">
                  ⚑
                </span>
              )}
            </button>
          );
        })}
      </div>
      <ul aria-label={t('navigator.legend')} className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted">
        <li>■ {t('navigator.answered')}</li>
        <li>□ {t('navigator.unanswered')}</li>
        <li>⚑ {t('navigator.flagged')}</li>
      </ul>
    </div>
  );
}
