import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../design';
import { videoEmbedSrc } from '../cms/lesson-editor/blocks';
import type { Block } from '../cms/lesson-editor/types';

export type StoredFileKind = 'IMAGE' | 'AUDIO' | 'VIDEO';

/** What a learner may see of a question: its content and nothing from the solution side (the server's `examView` has no field for it). */
export interface EmbeddedQuestionData {
  versionId: string;
  typeCode: string;
  content: { stem?: string; options?: { key: string; text: string }[]; [key: string]: unknown };
}

/** The server's verdict on a learner's answer. The answer and the explanation only exist here, after the learner has answered. */
export interface AnswerFeedback {
  correct: boolean;
  correctAnswer?: string;
  explanation?: string;
}

export interface StrategyLessonViewProps {
  title: string;
  blocks: Block[];
  questionTypeCodes?: string[];
  /** The address a stored file is shown from (a signed link the lesson page obtained for this learner), or undefined when it cannot be opened. */
  mediaSrc: (kind: StoredFileKind, mediaId: string) => string | undefined;
  /** The learner view of each embedded question, by question version id; a missing one is announced, not hidden. */
  questions: Record<string, EmbeddedQuestionData | undefined>;
  /** Checks an answer on the server; without it an embedded question is shown but cannot be answered. */
  checkAnswer?: (questionVersionId: string, answer: string) => Promise<AnswerFeedback>;
}

/**
 * A strategy lesson as the learner reads it (F14). Pure display: the lesson page (P3.2) fetches the lesson, the signed file links, the learner view of the
 * embedded questions and the checker, and passes them in. Text is rendered as text; a video-embed is framed only from a rebuilt YouTube / Vimeo address
 * (the page's CSP frame-src allows exactly those two origins); the answer of an embedded question appears only after the learner has answered.
 */
export function StrategyLessonView({ title, blocks, questionTypeCodes, mediaSrc, questions, checkAnswer }: StrategyLessonViewProps) {
  const { t } = useTranslation('lessons');
  return (
    <article className="flex max-w-prose flex-col gap-5 font-serif">
      <h1 className="text-3xl font-bold text-ink">{title}</h1>
      {questionTypeCodes && questionTypeCodes.length > 0 && (
        <p className="font-sans text-sm text-muted">
          {t('view.types')}: {questionTypeCodes.map((c) => c).join(', ')}
        </p>
      )}
      {blocks.map((b, i) => (
        <BlockView key={i} block={b} mediaSrc={mediaSrc} questions={questions} checkAnswer={checkAnswer} />
      ))}
    </article>
  );
}

function Caption({ text }: { text?: string }) {
  return text ? <figcaption className="mt-1 font-sans text-sm text-muted">{text}</figcaption> : null;
}

function BlockView({ block, mediaSrc, questions, checkAnswer }: { block: Block } & Pick<StrategyLessonViewProps, 'mediaSrc' | 'questions' | 'checkAnswer'>) {
  const { t } = useTranslation('lessons');
  switch (block.type) {
    case 'heading': {
      const H = (`h${Math.min(4, Math.max(2, block.level))}`) as 'h2' | 'h3' | 'h4';
      return <H className="mt-2 text-2xl font-bold text-ink">{block.text}</H>;
    }
    case 'paragraph':
      return <p className="whitespace-pre-line leading-relaxed text-ink">{block.text}</p>;
    case 'table':
      return (
        <figure className="overflow-x-auto font-sans">
          <table className="w-full border-collapse text-left text-sm">
            {block.caption && <caption className="mb-1 text-left text-sm text-muted">{block.caption}</caption>}
            <thead>
              <tr>
                {block.header.map((h, i) => (
                  <th key={i} scope="col" className="border-b-2 border-edge px-3 py-2 font-semibold text-ink">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((r, i) => (
                <tr key={i}>
                  {r.map((c, j) => (
                    <td key={j} className="border-b border-line px-3 py-2 text-ink">
                      {c}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </figure>
      );
    case 'image': {
      const src = mediaSrc('IMAGE', block.imageMediaId);
      return (
        <figure>
          {src ? <img src={src} alt={block.alt} className="max-w-full rounded-md" /> : <p className="font-sans text-sm text-muted">{t('view.mediaMissing')}</p>}
          <Caption text={block.caption} />
        </figure>
      );
    }
    case 'audio': {
      const src = mediaSrc('AUDIO', block.audioMediaId);
      return (
        <figure>
          {src ? <audio controls preload="metadata" src={src} aria-label={block.caption ?? t('blockType.audio')} className="w-full" /> : <p className="font-sans text-sm text-muted">{t('view.mediaMissing')}</p>}
          <Caption text={block.caption} />
        </figure>
      );
    }
    case 'video-upload': {
      const src = mediaSrc('VIDEO', block.videoMediaId);
      return (
        <figure>
          {src ? <video controls preload="metadata" src={src} aria-label={block.caption ?? t('blockType.video-upload')} className="w-full rounded-md" /> : <p className="font-sans text-sm text-muted">{t('view.mediaMissing')}</p>}
          <Caption text={block.caption} />
        </figure>
      );
    }
    case 'video-embed': {
      const src = videoEmbedSrc(block.provider, block.url);
      return (
        <figure>
          {src ? (
            <iframe
              src={src}
              title={t('view.video', { caption: block.caption ?? block.provider })}
              allow="fullscreen; picture-in-picture"
              allowFullScreen
              loading="lazy"
              referrerPolicy="strict-origin-when-cross-origin"
              sandbox="allow-scripts allow-same-origin allow-presentation"
              className="aspect-video w-full rounded-md border-0"
            />
          ) : (
            <p role="note" className="font-sans text-sm text-muted">
              {t('view.videoBlocked')}
            </p>
          )}
          <Caption text={block.caption} />
        </figure>
      );
    }
    case 'callout':
      return (
        <aside
          aria-label={t(`view.${block.kind}`)}
          className={`rounded-md border-l-4 p-3 font-sans ${block.kind === 'tip' ? 'border-success bg-success-bg' : 'border-danger bg-danger-bg'}`}
        >
          <p className="text-sm font-bold text-ink">{t(`view.${block.kind}`)}</p>
          <p className="whitespace-pre-line text-ink">{block.text}</p>
        </aside>
      );
    case 'embedded-question': {
      const q = questions[block.questionVersionId];
      return q ? <EmbeddedQuestion question={q} checkAnswer={checkAnswer} /> : <p className="font-sans text-sm text-muted">{t('view.questionMissing')}</p>;
    }
    case 'worked-example':
      return <WorkedExample block={block} />;
  }
}

function WorkedExample({ block }: { block: Extract<Block, { type: 'worked-example' }> }) {
  const { t } = useTranslation('lessons');
  const [shown, setShown] = useState(false);
  return (
    <section aria-label={`${t('view.workedExample')}: ${block.title}`} className="rounded-md border border-line bg-surface p-4 font-sans">
      <h3 className="text-lg font-bold text-ink">{block.title}</h3>
      <p className="mt-1 text-sm font-semibold text-muted">{t('view.problem')}</p>
      <p className="whitespace-pre-line text-ink">{block.problem}</p>
      <ol className="mt-3 list-decimal pl-5 text-ink">
        {block.steps.map((s, i) => (
          <li key={i} className="mt-1">
            {s}
          </li>
        ))}
      </ol>
      {block.answer && (
        <div className="mt-3">
          <Button variant="secondary" size="sm" aria-expanded={shown} onClick={() => setShown(!shown)}>
            {t('view.showAnswer')}
          </Button>
          {shown && (
            <p className="mt-2 text-ink">
              <strong>{t('view.answer')}:</strong> {block.answer}
            </p>
          )}
        </div>
      )}
    </section>
  );
}

function EmbeddedQuestion({ question, checkAnswer }: { question: EmbeddedQuestionData; checkAnswer?: StrategyLessonViewProps['checkAnswer'] }) {
  const { t } = useTranslation('lessons');
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<AnswerFeedback | null>(null);
  const [failed, setFailed] = useState(false);
  const { stem, options } = question.content;
  const name = `eq-${question.versionId}`;
  const answered = feedback !== null;

  async function check() {
    if (!checkAnswer || !answer) return;
    setBusy(true);
    setFailed(false);
    try {
      setFeedback(await checkAnswer(question.versionId, answer));
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label={t('view.question')} className="rounded-md border border-edge bg-surface p-4 font-sans">
      <h3 className="text-sm font-bold text-muted">{t('view.question')}</h3>
      {stem && <p className="mt-1 text-ink">{stem}</p>}
      {Array.isArray(options) && options.length > 0 ? (
        <fieldset disabled={answered || !checkAnswer} className="mt-2 flex flex-col gap-1">
          <legend className="sr-only">{t('view.yourAnswer')}</legend>
          {options.map((o) => (
            <label key={o.key} className="flex items-start gap-2 text-ink">
              <input type="radio" name={name} value={o.key} checked={answer === o.key} onChange={() => setAnswer(o.key)} />
              <span>
                <strong>{o.key}.</strong> {o.text}
              </span>
            </label>
          ))}
        </fieldset>
      ) : (
        <label className="mt-2 flex flex-col gap-1 text-sm font-semibold text-ink">
          {t('view.yourAnswer')}
          <input type="text" value={answer} disabled={answered || !checkAnswer} onChange={(e) => setAnswer(e.target.value)} className="min-h-10 rounded-md border border-edge bg-surface px-3 font-normal" />
        </label>
      )}
      {checkAnswer && !answered && (
        <Button className="mt-3" size="sm" loading={busy} disabled={!answer} onClick={() => void check()}>
          {busy ? t('view.checking') : t('view.check')}
        </Button>
      )}
      {failed && (
        <p role="alert" className="mt-2 text-sm font-medium text-danger">
          {t('errors.generic')}
        </p>
      )}
      {feedback && (
        <div role="status" className="mt-3 text-ink">
          <p className="font-bold">{feedback.correct ? t('view.correct') : t('view.wrong')}</p>
          {feedback.correctAnswer && <p>{t('view.correctAnswer', { answer: feedback.correctAnswer })}</p>}
          {feedback.explanation && (
            <p>
              <strong>{t('view.explanation')}:</strong> {feedback.explanation}
            </p>
          )}
        </div>
      )}
    </section>
  );
}
