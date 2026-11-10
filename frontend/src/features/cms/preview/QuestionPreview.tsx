import { useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../../design';
import { MediaPreview } from '../media';
import { SOLUTION_FIELDS, type InteractionKind } from '../question-form/kinds';
import type { Json, JsonObject } from '../questions/types';

/**
 * What a learner may see of a question's content. The content of a type has no solution field (the answer key, the explanation, the transcript and
 * the model answer live next to it, on the solution side), but the preview does not rely on that: any key of the solution side, at any depth, is dropped
 * before anything is drawn. The preview component takes the content and NOTHING else, so there is nothing to leak from.
 */
export function learnerContent(content: JsonObject): JsonObject {
  const strip = (node: Json): Json => {
    if (Array.isArray(node)) return node.map(strip);
    if (node && typeof node === 'object') {
      const out: JsonObject = {};
      for (const [k, v] of Object.entries(node)) if (!(SOLUTION_FIELDS as readonly string[]).includes(k)) out[k] = strip(v as Json);
      return out;
    }
    return node;
  };
  return strip(content) as JsonObject;
}

type Option = { key: string; text: string };

const text = (v: Json | undefined): string => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '');
const list = (v: Json | undefined): JsonObject[] => (Array.isArray(v) ? (v.filter((x) => x && typeof x === 'object' && !Array.isArray(x)) as JsonObject[]) : []);
const strings = (v: Json | undefined): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
const options = (v: Json | undefined): Option[] => list(v).map((o, i) => ({ key: text(o.key) || String(i + 1), text: text(o.text) }));

export interface QuestionPreviewProps {
  kind: InteractionKind;
  /** The content of the question version; the solution side is not a prop of this component. */
  content: JsonObject;
}

/**
 * "Xem trước như học viên": the question as a learner meets it, built from the content and the interaction kind. This is the minimal renderer of the CMS;
 * the exam room (P3.4) replaces it with the real component. The controls take input so the author can feel the question, but nothing is saved or checked.
 */
export function QuestionPreview({ kind, content: raw }: QuestionPreviewProps) {
  const { t } = useTranslation('preview');
  const content = learnerContent(raw);
  return (
    <section aria-label={t('region')} className="flex max-w-prose flex-col gap-4 rounded-lg border border-line bg-surface p-5">
      <Media content={content} />
      <Body kind={kind} content={content} />
    </section>
  );
}

function Media({ content }: { content: JsonObject }) {
  const { t } = useTranslation('preview');
  const audio = text(content.audioMediaId);
  const image = text(content.imageMediaId);
  if (!audio && !image) return null;
  return (
    <div className="flex flex-col gap-3">
      {audio && <MediaPreview mediaId={audio} kind="AUDIO" name={t('audio')} />}
      {image && <MediaPreview mediaId={image} kind="IMAGE" name={t('image')} />}
    </div>
  );
}

function Prompt({ children }: { children: ReactNode }) {
  return <p className="font-serif text-lg text-ink">{children}</p>;
}

function Body({ kind, content }: QuestionPreviewProps) {
  const { t } = useTranslation('preview');
  const name = useId();
  const opts = options(content.options);
  const stem = text(content.stem);
  switch (kind) {
    case 'MCQ_SINGLE':
    case 'GAP_SELECT':
      return (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 font-serif text-lg text-ink">
            {kind === 'GAP_SELECT' && content.gapNumber !== undefined && <span className="mr-2 font-sans text-sm text-muted">{t('gap', { n: text(content.gapNumber) })}</span>}
            {stem || t('chooseOne')}
          </legend>
          {opts.map((o) => (
            <label key={o.key} className="flex items-start gap-2 text-ink">
              <input type="radio" name={name} value={o.key} className="mt-1" />
              <span>
                <span className="font-semibold">{o.key}.</span> {o.text}
              </span>
            </label>
          ))}
        </fieldset>
      );
    case 'MCQ_MULTI':
      return (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 font-serif text-lg text-ink">{stem}</legend>
          {content.selectCount !== undefined && <p className="text-sm text-muted">{t('chooseN', { n: text(content.selectCount) })}</p>}
          {opts.map((o) => (
            <label key={o.key} className="flex items-start gap-2 text-ink">
              <input type="checkbox" value={o.key} className="mt-1" />
              <span>
                <span className="font-semibold">{o.key}.</span> {o.text}
              </span>
            </label>
          ))}
        </fieldset>
      );
    case 'GAP_TYPED':
      return (
        <div className="flex flex-col gap-3">
          {text(content.title) && <h2 className="font-serif text-xl font-bold text-ink">{text(content.title)}</h2>}
          <Gaps source={text(content.text)} />
        </div>
      );
    case 'MATCHING': {
      const choices = options(content.choices);
      return (
        <div className="flex flex-col gap-3">
          {text(content.instruction) && <Prompt>{text(content.instruction)}</Prompt>}
          <ul className="flex flex-col gap-3">
            {list(content.items).map((it, i) => (
              <li key={i} className="flex flex-wrap items-center gap-3">
                <span className="min-w-8 font-semibold text-ink">{i + 1}.</span>
                <span className="text-ink">{text(it.text)}</span>
                <select aria-label={t('matchFor', { n: i + 1 })} defaultValue="" className="min-h-9 rounded-md border border-edge bg-surface px-2 text-ink">
                  <option value="">{t('pick')}</option>
                  {choices.map((c) => (
                    <option key={c.key} value={c.key}>
                      {c.key}. {c.text}
                    </option>
                  ))}
                </select>
              </li>
            ))}
          </ul>
          <ul className="list-none text-sm text-muted">
            {choices.map((c) => (
              <li key={c.key}>
                <span className="font-semibold">{c.key}.</span> {c.text}
              </li>
            ))}
          </ul>
        </div>
      );
    }
    case 'TRUE_FALSE_NG':
    case 'YES_NO_NG': {
      const answers = kind === 'TRUE_FALSE_NG' ? ['TRUE', 'FALSE', 'NOT GIVEN'] : ['YES', 'NO', 'NOT GIVEN'];
      return (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 font-serif text-lg text-ink">{text(content.statement)}</legend>
          {answers.map((a) => (
            <label key={a} className="flex items-center gap-2 text-ink">
              <input type="radio" name={name} value={a} /> {a}
            </label>
          ))}
        </fieldset>
      );
    }
    case 'LABEL_DIAGRAM': {
      const choices = options(content.choices);
      const label = text(content.labelNumber);
      return (
        <div className="flex flex-col gap-2">
          <Prompt>{t('label', { n: label })}</Prompt>
          {choices.length > 0 ? (
            <select aria-label={t('label', { n: label })} defaultValue="" className="min-h-9 self-start rounded-md border border-edge bg-surface px-2 text-ink">
              <option value="">{t('pick')}</option>
              {choices.map((c) => (
                <option key={c.key} value={c.key}>
                  {c.key}. {c.text}
                </option>
              ))}
            </select>
          ) : (
            <input type="text" aria-label={t('label', { n: label })} className="min-h-9 self-start rounded-md border border-edge bg-surface px-2 text-ink" />
          )}
        </div>
      );
    }
    case 'SHORT_ANSWER':
      return (
        <div className="flex flex-col gap-2">
          <label htmlFor={`${name}-a`}>
            <Prompt>{stem}</Prompt>
          </label>
          <input id={`${name}-a`} type="text" autoComplete="off" className="min-h-10 rounded-md border border-edge bg-surface px-3 text-ink" />
        </div>
      );
    case 'ORDERING':
      return <Ordering content={content} />;
    case 'ESSAY':
      return (
        <div className="flex flex-col gap-2">
          <Prompt>{text(content.prompt)}</Prompt>
          {content.minWords !== undefined && <p className="text-sm text-muted">{t('minWords', { n: text(content.minWords) })}</p>}
          <label htmlFor={`${name}-essay`} className="text-sm font-semibold text-ink">
            {t('yourAnswer')}
          </label>
          <textarea id={`${name}-essay`} rows={8} className="w-full rounded-md border border-edge bg-surface p-3 text-ink" />
        </div>
      );
    case 'SPEAKING_RESPONSE': {
      const qs = strings(content.questions);
      const bullets = strings(content.bulletPoints);
      return (
        <div className="flex flex-col gap-3">
          <Prompt>{text(content.topic)}</Prompt>
          {bullets.length > 0 && (
            <ul className="list-disc pl-6 text-ink">
              {bullets.map((b) => (
                <li key={b}>{b}</li>
              ))}
            </ul>
          )}
          {qs.length > 0 && (
            <ol className="list-decimal pl-6 text-ink">
              {qs.map((q) => (
                <li key={q}>{q}</li>
              ))}
            </ol>
          )}
          {(content.prepareSeconds !== undefined || content.speakSeconds !== undefined) && (
            <p className="text-sm text-muted">{t('timing', { prepare: text(content.prepareSeconds) || '-', speak: text(content.speakSeconds) || '-' })}</p>
          )}
          <Button variant="secondary" size="sm" disabled className="self-start">
            {t('record')}
          </Button>
        </div>
      );
    }
  }
}

/** `Name: [[1]]  Phone: [[2]]` -> the text with an input where each `[[n]]` is. */
function Gaps({ source }: { source: string }) {
  const { t } = useTranslation('preview');
  const parts = source.split(/\[\[(\d+)]]/);
  return (
    <p className="font-serif text-lg leading-10 text-ink">
      {parts.map((p, i) =>
        i % 2 === 1 ? (
          <input key={i} type="text" aria-label={t('gap', { n: p })} autoComplete="off" className="mx-1 inline-block min-h-9 w-36 rounded-md border border-edge bg-surface px-2 align-middle text-ink" />
        ) : (
          <span key={i}>{p}</span>
        ),
      )}
    </p>
  );
}

function Ordering({ content }: { content: JsonObject }) {
  const { t } = useTranslation('preview');
  const items = options(content.items);
  const [order, setOrder] = useState(() => items.map((_, i) => i));
  const move = (from: number, to: number) => {
    if (to < 0 || to >= order.length) return;
    const next = [...order];
    const [x] = next.splice(from, 1);
    next.splice(to, 0, x!);
    setOrder(next);
  };
  return (
    <div className="flex flex-col gap-3">
      {text(content.instruction) && <Prompt>{text(content.instruction)}</Prompt>}
      <ol className="flex flex-col gap-2">
        {order.map((idx, pos) => (
          <li key={idx} className="flex items-center gap-2 text-ink">
            <span className="min-w-6 font-semibold">{pos + 1}.</span>
            <span className="flex-1">{items[idx]?.text}</span>
            <Button variant="secondary" size="sm" aria-label={t('moveUp', { n: pos + 1 })} disabled={pos === 0} onClick={() => move(pos, pos - 1)}>
              ↑
            </Button>
            <Button variant="secondary" size="sm" aria-label={t('moveDown', { n: pos + 1 })} disabled={pos === order.length - 1} onClick={() => move(pos, pos + 1)}>
              ↓
            </Button>
          </li>
        ))}
      </ol>
    </div>
  );
}
