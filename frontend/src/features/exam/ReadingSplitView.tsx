import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/** Passage on one side, questions on the other; stacked below 768 px. Each side scrolls by itself. */
export function ReadingSplitView({ passage, title, children }: { passage: string; title?: string; children: ReactNode }) {
  const { t } = useTranslation('exam');
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <section aria-label={t('reading.passage')} className="max-h-[70vh] overflow-auto rounded-md border border-line bg-surface p-4 text-ink" tabIndex={0}>
        {title && <h2 className="mb-2 font-serif text-xl font-bold">{title}</h2>}
        {passage.split(/\n\s*\n/).map((p, i) => (
          <p key={i} className="mb-3 font-serif leading-relaxed">{p}</p>
        ))}
      </section>
      <div className="max-h-[70vh] overflow-auto p-1">{children}</div>
    </div>
  );
}
