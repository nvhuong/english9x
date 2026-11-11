import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../design';
import { WritingEditor } from './WritingEditor';
import { gapsOf, objects, optionsOf, stringsOf, text } from './kinds';
import type { InteractionKind, Json, JsonObject } from './types';

export interface QuestionInputProps {
  kind: InteractionKind;
  content: JsonObject;
  value: Json | undefined;
  onChange: (value: Json) => void;
  /** The number the candidate sees, for labels. */
  number: number;
}

const asString = (v: Json | undefined): string => (typeof v === 'string' ? v : '');
const asList = (v: Json | undefined): string[] => (Array.isArray(v) ? v.map((x) => (typeof x === 'string' ? x : '')) : []);

const selectClass = 'min-h-9 rounded-md border border-edge bg-surface px-2 text-ink';
const inputClass = 'min-h-9 rounded-md border border-edge bg-surface px-2 text-ink';

/** The answer control of one question, for every InteractionKind of the catalog. Controlled; knows nothing about saving. Speaking has its own component. */
export function QuestionInput({ kind, content, value, onChange, number }: QuestionInputProps) {
  const { t } = useTranslation('exam');
  const name = useId();
  const stem = text(content.stem);
  const options = optionsOf(content.options);
  const legend = (
    <legend className="mb-2 font-serif text-lg text-ink">
      <span className="mr-2 font-sans text-sm font-bold text-muted">{number}.</span>
      {stem}
    </legend>
  );
  switch (kind) {
    case 'MCQ_SINGLE':
    case 'GAP_SELECT':
      return (
        <fieldset className="flex flex-col gap-2">
          {kind === 'GAP_SELECT' && content.gapNumber !== undefined ? (
            <legend className="mb-2 font-serif text-lg text-ink">{t('q.gap', { n: text(content.gapNumber) || number })} {stem}</legend>
          ) : (
            legend
          )}
          {options.map((o) => (
            <label key={o.key} className="flex items-start gap-2 text-ink">
              <input type="radio" name={name} value={o.key} checked={asString(value) === o.key} onChange={() => onChange(o.key)} className="mt-1" />
              <span>
                <span className="font-semibold">{o.key}.</span> {o.text}
              </span>
            </label>
          ))}
        </fieldset>
      );
    case 'MCQ_MULTI': {
      const chosen = asList(value);
      const limit = typeof content.selectCount === 'number' ? content.selectCount : undefined;
      return (
        <fieldset className="flex flex-col gap-2">
          {legend}
          {limit !== undefined && <p className="text-sm text-muted">{t('q.chooseN', { n: limit })}</p>}
          {options.map((o) => (
            <label key={o.key} className="flex items-start gap-2 text-ink">
              <input
                type="checkbox"
                value={o.key}
                checked={chosen.includes(o.key)}
                onChange={(e) => {
                  const next = e.target.checked ? [...chosen, o.key] : chosen.filter((k) => k !== o.key);
                  if (limit === undefined || next.length <= limit) onChange(next);
                }}
                className="mt-1"
              />
              <span>
                <span className="font-semibold">{o.key}.</span> {o.text}
              </span>
            </label>
          ))}
        </fieldset>
      );
    }
    case 'TRUE_FALSE_NG':
    case 'YES_NO_NG': {
      const answers = kind === 'TRUE_FALSE_NG' ? ['TRUE', 'FALSE', 'NOT GIVEN'] : ['YES', 'NO', 'NOT GIVEN'];
      return (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 font-serif text-lg text-ink">
            <span className="mr-2 font-sans text-sm font-bold text-muted">{number}.</span>
            {text(content.statement)}
          </legend>
          {answers.map((a) => (
            <label key={a} className="flex items-center gap-2 text-ink">
              <input type="radio" name={name} value={a} checked={asString(value) === a} onChange={() => onChange(a)} /> {a}
            </label>
          ))}
        </fieldset>
      );
    }
    case 'GAP_TYPED': {
      const source = text(content.text);
      const parts = source.split(/\[\[(\d+)]]/);
      const gaps = gapsOf(source);
      const given = asList(value);
      const set = (i: number, v: string) => {
        const next = gaps.map((_, k) => given[k] ?? '');
        next[i] = v;
        onChange(next);
      };
      return (
        <div className="flex flex-col gap-2">
          {text(content.title) && <h3 className="font-serif text-lg font-bold text-ink">{text(content.title)}</h3>}
          <p className="font-serif text-lg leading-10 text-ink">
            {parts.map((p, i) => {
              if (i % 2 === 0) return <span key={i}>{p}</span>;
              const at = (i - 1) / 2;
              return (
                <input key={i} type="text" aria-label={t('q.gap', { n: p })} value={given[at] ?? ''} onChange={(e) => set(at, e.target.value)} autoComplete="off" spellCheck={false} className={`mx-1 inline-block w-36 align-middle ${inputClass}`} />
              );
            })}
          </p>
        </div>
      );
    }
    case 'MATCHING': {
      const choices = optionsOf(content.choices);
      const items = objects(content.items);
      const given = asList(value);
      const set = (i: number, v: string) => {
        const next = items.map((_, k) => given[k] ?? '');
        next[i] = v;
        onChange(next);
      };
      return (
        <div className="flex flex-col gap-3">
          {text(content.instruction) && <p className="font-serif text-lg text-ink">{text(content.instruction)}</p>}
          <ul className="flex flex-col gap-3">
            {items.map((it, i) => (
              <li key={i} className="flex flex-wrap items-center gap-3">
                <span className="min-w-8 font-semibold text-ink">{number + i}.</span>
                <span className="text-ink">{text(it.text)}</span>
                <select aria-label={t('q.matchFor', { n: number + i })} value={given[i] ?? ''} onChange={(e) => set(i, e.target.value)} className={selectClass}>
                  <option value="">{t('q.pick')}</option>
                  {choices.map((c) => (
                    <option key={c.key} value={c.key}>{c.key}. {c.text}</option>
                  ))}
                </select>
              </li>
            ))}
          </ul>
          <ul className="list-none text-sm text-muted">
            {choices.map((c) => (
              <li key={c.key}><span className="font-semibold">{c.key}.</span> {c.text}</li>
            ))}
          </ul>
        </div>
      );
    }
    case 'LABEL_DIAGRAM': {
      const choices = optionsOf(content.choices);
      const label = text(content.labelNumber) || String(number);
      return (
        <div className="flex flex-col gap-2">
          <p className="font-serif text-lg text-ink">{t('q.label', { n: label })}</p>
          {choices.length > 0 ? (
            <select aria-label={t('q.label', { n: label })} value={asString(value)} onChange={(e) => onChange(e.target.value)} className={`self-start ${selectClass}`}>
              <option value="">{t('q.pick')}</option>
              {choices.map((c) => (
                <option key={c.key} value={c.key}>{c.key}. {c.text}</option>
              ))}
            </select>
          ) : (
            <input type="text" aria-label={t('q.label', { n: label })} value={asString(value)} onChange={(e) => onChange(e.target.value)} autoComplete="off" spellCheck={false} className={`self-start ${inputClass}`} />
          )}
        </div>
      );
    }
    case 'SHORT_ANSWER':
      return (
        <div className="flex flex-col gap-2">
          <label htmlFor={`${name}-a`} className="font-serif text-lg text-ink">
            <span className="mr-2 font-sans text-sm font-bold text-muted">{number}.</span>
            {stem}
          </label>
          <input id={`${name}-a`} type="text" value={asString(value)} onChange={(e) => onChange(e.target.value)} autoComplete="off" spellCheck={false} className={`min-h-10 px-3 ${inputClass}`} />
        </div>
      );
    case 'ORDERING': {
      const items = optionsOf(content.items);
      const order = (() => {
        const given = asList(value).filter((k) => items.some((i) => i.key === k));
        return given.length === items.length ? given : items.map((i) => i.key);
      })();
      const move = (from: number, to: number) => {
        if (to < 0 || to >= order.length) return;
        const next = [...order];
        const [x] = next.splice(from, 1);
        next.splice(to, 0, x!);
        onChange(next);
      };
      return (
        <div className="flex flex-col gap-3">
          {text(content.instruction) && <p className="font-serif text-lg text-ink">{text(content.instruction)}</p>}
          <ol className="flex flex-col gap-2">
            {order.map((k, pos) => (
              <li key={k} className="flex items-center gap-2 text-ink">
                <span className="min-w-6 font-semibold">{pos + 1}.</span>
                <span className="flex-1">{items.find((i) => i.key === k)?.text}</span>
                <Button variant="secondary" size="sm" aria-label={t('q.moveUp', { n: pos + 1 })} disabled={pos === 0} onClick={() => move(pos, pos - 1)}>↑</Button>
                <Button variant="secondary" size="sm" aria-label={t('q.moveDown', { n: pos + 1 })} disabled={pos === order.length - 1} onClick={() => move(pos, pos + 1)}>↓</Button>
              </li>
            ))}
          </ol>
        </div>
      );
    }
    case 'ESSAY':
      return (
        <div className="flex flex-col gap-3">
          <p className="font-serif text-lg text-ink">{text(content.prompt)}</p>
          <WritingEditor value={asString(value)} onChange={onChange} minWords={typeof content.minWords === 'number' ? content.minWords : undefined} />
        </div>
      );
    case 'SPEAKING_RESPONSE': {
      const bullets = stringsOf(content.bulletPoints);
      const qs = stringsOf(content.questions);
      return (
        <div className="flex flex-col gap-3">
          <p className="font-serif text-lg text-ink">{text(content.topic)}</p>
          {bullets.length > 0 && <ul className="list-disc pl-6 text-ink">{bullets.map((b) => <li key={b}>{b}</li>)}</ul>}
          {qs.length > 0 && <ol className="list-decimal pl-6 text-ink">{qs.map((q) => <li key={q}>{q}</li>)}</ol>}
        </div>
      );
    }
  }
}
