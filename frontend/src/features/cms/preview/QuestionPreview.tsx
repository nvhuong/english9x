import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../../design';
import { MediaPreview } from '../media';
import { QuestionInput } from '../../exam';
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

const text = (v: Json | undefined): string => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '');

export interface QuestionPreviewProps {
  kind: InteractionKind;
  /** The content of the question version; the solution side is not a prop of this component. */
  content: JsonObject;
}

/**
 * "Xem trước như học viên": the question as a learner meets it, built from the content and the interaction kind. The answer control is the exam room's own QuestionInput, so the preview looks like the room. The controls take input so the author can feel the question, but nothing is saved or checked.
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

function Body({ kind, content }: QuestionPreviewProps) {
  const { t } = useTranslation('preview');
  const [value, setValue] = useState<Json | undefined>(undefined);
  return (
    <>
      <QuestionInput kind={kind} content={content} value={value} onChange={setValue} number={1} />
      {kind === 'SPEAKING_RESPONSE' && (
        <Button variant="secondary" size="sm" disabled className="self-start">
          {t('record')}
        </Button>
      )}
    </>
  );
}
