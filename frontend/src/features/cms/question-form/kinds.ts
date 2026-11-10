import type { QuestionTypeForm } from '../questions/types';

/** The twelve ways a learner responds to an item (the server's `InteractionKind`); the catalog's `kind` is one of these. */
export const INTERACTION_KINDS = [
  'MCQ_SINGLE',
  'MCQ_MULTI',
  'GAP_TYPED',
  'GAP_SELECT',
  'MATCHING',
  'TRUE_FALSE_NG',
  'YES_NO_NG',
  'LABEL_DIAGRAM',
  'SHORT_ANSWER',
  'ORDERING',
  'ESSAY',
  'SPEAKING_RESPONSE',
] as const;
export type InteractionKind = (typeof INTERACTION_KINDS)[number];

export const isInteractionKind = (k: unknown): k is InteractionKind => (INTERACTION_KINDS as readonly unknown[]).includes(k);

/** The kind of a type's form, or undefined when the server sent one this screen does not know (the form still renders from the schema). */
export const kindOf = (type: Pick<QuestionTypeForm, 'kind'>): InteractionKind | undefined => (isInteractionKind(type.kind) ? type.kind : undefined);

/** Fields of the solution side. They live in the answer key (and the explanation) and are never part of what a learner is shown. */
export const SOLUTION_FIELDS = ['answerKey', 'answer', 'answers', 'slots', 'explanation', 'transcript', 'sampleAnswer', 'solution', 'correct', 'correctAnswer', 'notes'] as const;
