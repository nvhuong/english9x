import type { JsonObject, QuestionTypeForm } from '../questions/types';
import { INTERACTION_KINDS, type InteractionKind } from './kinds';

const str = { type: 'string', title: 'x' };
const keyText = { type: 'array', items: { type: 'object', properties: { key: str, text: str }, required: ['key', 'text'] } };
const slots = { type: 'array', items: { type: 'array', items: { type: 'string' } } };
const mediaId = { type: 'string', title: 'Media' };

/** Content schema (the properties the real catalog gives this kind) and what the solution side adds, for each interaction kind. */
const SHAPES: Record<InteractionKind, { content: JsonObject; answerKey: JsonObject }> = {
  MCQ_SINGLE: { content: { stem: str, options: keyText, audioMediaId: mediaId }, answerKey: { slots, transcript: str } },
  MCQ_MULTI: { content: { stem: str, options: keyText, selectCount: { type: 'integer' } }, answerKey: { slots } },
  GAP_TYPED: { content: { title: str, text: str, imageMediaId: mediaId }, answerKey: { slots, maxWords: { type: 'integer' } } },
  GAP_SELECT: { content: { gapNumber: { type: 'integer' }, stem: str, options: keyText }, answerKey: { slots } },
  MATCHING: { content: { instruction: str, items: { type: 'array', items: { type: 'object', properties: { text: str } } }, choices: keyText, allowReuse: { type: 'boolean' } }, answerKey: { slots } },
  TRUE_FALSE_NG: { content: { statement: str }, answerKey: { slots } },
  YES_NO_NG: { content: { statement: str }, answerKey: { slots } },
  LABEL_DIAGRAM: { content: { imageMediaId: mediaId, labelNumber: { type: 'integer' }, marker: { type: 'object', properties: { x: { type: 'number' }, y: { type: 'number' } } }, choices: keyText }, answerKey: { slots } },
  SHORT_ANSWER: { content: { stem: str }, answerKey: { slots, maxWords: { type: 'integer' } } },
  ORDERING: { content: { instruction: str, items: { type: 'array', items: { type: 'object', properties: { key: str, text: str } } } }, answerKey: { slots } },
  ESSAY: { content: { prompt: str, minWords: { type: 'integer' } }, answerKey: { sampleAnswer: str, notes: str } },
  SPEAKING_RESPONSE: { content: { topic: str, questions: { type: 'array', items: { type: 'string' } } }, answerKey: { sampleAnswer: str } },
};

/** A question type of the given kind, shaped like the server's catalog entry. */
export function typeOfKind(kind: InteractionKind): QuestionTypeForm {
  const s = SHAPES[kind];
  return {
    code: `TEST_${kind}`,
    examCode: kind.startsWith('GAP') || kind === 'ESSAY' || kind === 'SPEAKING_RESPONSE' ? 'IELTS' : 'TOEIC',
    section: 'S',
    kind,
    contentSchema: { $schema: 'https://json-schema.org/draft/2020-12/schema', type: 'object', properties: s.content },
    answerKeySchema: { $schema: 'https://json-schema.org/draft/2020-12/schema', type: 'object', properties: s.answerKey },
  };
}

export const allKindTypes = (): QuestionTypeForm[] => INTERACTION_KINDS.map(typeOfKind);
