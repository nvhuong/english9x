import { useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from './Button';
import { cn } from './cn';

/** Inclusive range of word indices (words counted across the whole text, from 0). */
export type WordRange = readonly [start: number, end: number];

export interface HighlighterProps {
  /** Passage text; blank lines separate paragraphs. */
  text: string;
  defaultHighlights?: readonly WordRange[];
  onChange?: (ranges: WordRange[]) => void;
  className?: string;
}

interface Word {
  index: number;
  text: string;
  paragraph: number;
}

function tokenize(text: string): Word[] {
  const words: Word[] = [];
  text.split(/\n\s*\n/).forEach((para, p) => {
    for (const w of para.split(/\s+/).filter(Boolean)) words.push({ index: words.length, text: w, paragraph: p });
  });
  return words;
}

function toRanges(set: ReadonlySet<number>): WordRange[] {
  const out: Array<[number, number]> = [];
  for (const i of [...set].sort((a, b) => a - b)) {
    const last = out[out.length - 1];
    if (last && last[1] === i - 1) last[1] = i;
    else out.push([i, i]);
  }
  return out;
}

/**
 * Reading-passage highlighter, usable without a mouse. Words are a single tab stop (roving tabindex):
 * Left/Right move, Shift+Left/Right select, Enter/Space toggle highlight, Escape clears the selection, Home/End jump.
 * Mouse users select text and press the Highlight button. Highlights are word ranges; P3.4 persists them.
 */
export function Highlighter({ text, defaultHighlights = [], onChange, className }: HighlighterProps) {
  const { t } = useTranslation();
  const words = useMemo(() => tokenize(text), [text]);
  const [marked, setMarked] = useState<Set<number>>(() => new Set(defaultHighlights.flatMap(([s, e]) => Array.from({ length: e - s + 1 }, (_, k) => s + k))));
  const [active, setActive] = useState(0);
  const [anchor, setAnchor] = useState<number | null>(null);
  const [announce, setAnnounce] = useState('');
  const refs = useRef(new Map<number, HTMLElement>());
  const uid = useId();

  const selection: [number, number] | null = anchor === null ? null : [Math.min(anchor, active), Math.max(anchor, active)];
  const inSelection = (i: number) => !!selection && i >= selection[0] && i <= selection[1];
  const target = (): number[] => {
    const [s, e] = selection ?? [active, active];
    return Array.from({ length: e - s + 1 }, (_, k) => s + k);
  };

  const commit = (next: Set<number>, message: string) => {
    setMarked(next);
    setAnnounce(message);
    onChange?.(toRanges(next));
  };
  const apply = (mode: 'toggle' | 'add' | 'remove') => {
    const idx = target();
    const next = new Set(marked);
    const allMarked = idx.every((i) => next.has(i));
    const remove = mode === 'remove' || (mode === 'toggle' && allMarked);
    for (const i of idx) {
      if (remove) next.delete(i);
      else next.add(i);
    }
    commit(next, remove ? t('highlighter.unhighlighted') : t('highlighter.highlighted'));
  };

  const go = (to: number, extend: boolean) => {
    const i = Math.max(0, Math.min(words.length - 1, to));
    if (extend) setAnchor((a) => a ?? active);
    else setAnchor(null);
    setActive(i);
    refs.current.get(i)?.focus();
    if (extend) setAnnounce(t('highlighter.selected', { count: Math.abs(i - (anchor ?? active)) + 1 }));
  };

  const onKeyDown = (e: KeyboardEvent, i: number) => {
    switch (e.key) {
      case 'ArrowRight': e.preventDefault(); go(i + 1, e.shiftKey); break;
      case 'ArrowLeft': e.preventDefault(); go(i - 1, e.shiftKey); break;
      case 'Home': e.preventDefault(); go(0, e.shiftKey); break;
      case 'End': e.preventDefault(); go(words.length - 1, e.shiftKey); break;
      case 'Enter':
      case ' ': e.preventDefault(); apply('toggle'); break;
      case 'Escape': setAnchor(null); break;
    }
  };

  // Mouse / touch selection: map the browser selection to word indices.
  const fromMouse = () => {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed) return;
    const idx = (n: Node | null) => Number((n instanceof Element ? n : n?.parentElement)?.closest('[data-w]')?.getAttribute('data-w') ?? NaN);
    const a = idx(sel.anchorNode);
    const b = idx(sel.focusNode);
    if (Number.isNaN(a) || Number.isNaN(b)) return;
    setAnchor(Math.min(a, b));
    setActive(Math.max(a, b));
    setAnnounce(t('highlighter.selected', { count: Math.abs(a - b) + 1 }));
  };

  const paragraphs = words.reduce<Word[][]>((acc, w) => {
    (acc[w.paragraph] ??= []).push(w);
    return acc;
  }, []);

  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <div role="toolbar" aria-label={t('highlighter.toolbar')} className="flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" onClick={() => apply('add')}>{t('highlighter.highlight')}</Button>
        <Button size="sm" variant="secondary" onClick={() => apply('remove')}>{t('highlighter.clear')}</Button>
        <Button size="sm" variant="ghost" onClick={() => commit(new Set(), t('highlighter.unhighlighted'))}>{t('highlighter.clearAll')}</Button>
      </div>
      <p id={`${uid}-help`} className="text-sm text-muted">{t('highlighter.instructions')}</p>
      <div role="group" aria-label={t('highlighter.label')} aria-describedby={`${uid}-help`} onMouseUp={fromMouse} className="prose-reading flex flex-col gap-4">
        {paragraphs.map((para, p) => (
          <p key={p}>
            {para.map((w, k) => {
              const m = marked.has(w.index);
              const nextMarked = marked.has(w.index + 1) && para[k + 1] !== undefined;
              const cls = cn('rounded-sm', inSelection(w.index) && 'underline decoration-accent decoration-2 underline-offset-4');
              return (
                <span key={w.index}>
                  {/* The focusable element keeps its identity when highlighted (focus must survive); <mark> is its child. */}
                  <span
                    ref={(el) => {
                      if (el) refs.current.set(w.index, el);
                      else refs.current.delete(w.index);
                    }}
                    data-w={w.index}
                    tabIndex={w.index === active ? 0 : -1}
                    onFocus={() => setActive(w.index)}
                    onKeyDown={(e) => onKeyDown(e, w.index)}
                    className={cls}
                  >
                    {m ? (
                      <mark className="rounded-sm bg-mark-bg text-mark">
                        {w.text}
                        {nextMarked ? ' ' : ''}
                      </mark>
                    ) : (
                      w.text
                    )}
                  </span>
                  {!(m && nextMarked) && k < para.length - 1 ? ' ' : ''}
                </span>
              );
            })}
          </p>
        ))}
      </div>
      <p role="status" className="sr-only">{announce}</p>
    </div>
  );
}
