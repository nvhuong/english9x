import type { QuestionTypeForm, QuestionVersion } from './types';

/** TOEIC Part 3 as the server serves it (shortened): a question, four options, an audio file; the answer key with the transcript on the solution side. */
export const TOEIC_P3: QuestionTypeForm = {
  code: 'TOEIC_P3_CONVERSATION',
  examCode: 'TOEIC',
  section: 'LISTENING_P3',
  kind: 'MCQ_SINGLE',
  contentSchema: {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    title: 'TOEIC Part 3 Conversations',
    type: 'object',
    properties: {
      stem: { title: 'Question', type: 'string', minLength: 1, maxLength: 2000 },
      options: {
        title: 'Options',
        type: 'array',
        minItems: 4,
        maxItems: 4,
        items: {
          type: 'object',
          properties: { key: { title: 'Option key', type: 'string' }, text: { title: 'Option text', type: 'string' } },
          required: ['key', 'text'],
        },
      },
      audioMediaId: { title: 'Audio media id', type: 'string' },
    },
    required: ['stem', 'options'],
  },
  answerKeySchema: {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    title: 'Correct option',
    type: 'object',
    properties: {
      slots: { title: 'Accepted answers', type: 'array', items: { type: 'array', items: { type: 'string' } } },
      transcript: { title: 'Transcript', type: 'string', maxLength: 20000 },
    },
    required: ['slots'],
  },
};

export const QUESTION: QuestionVersion = {
  versionId: '8a1f0c5e-6b0e-4d0e-9a52-1f3a5c7e9b21',
  questionId: '3c7e9b21-8a1f-4c5e-9b0e-6d0e9a521f3a',
  versionNo: 1,
  status: 'DRAFT',
  examCode: 'TOEIC',
  typeCode: 'TOEIC_P3_CONVERSATION',
  content: {
    stem: 'What are the speakers discussing?',
    options: [
      { key: 'A', text: 'one' },
      { key: 'B', text: 'two' },
      { key: 'C', text: 'three' },
      { key: 'D', text: 'four' },
    ],
  },
  answerKey: { slots: [['B']], transcript: 'Script of the recording.' },
  explanation: 'Because.',
  points: 1,
};
