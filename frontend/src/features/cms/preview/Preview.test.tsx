import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { INTERACTION_KINDS, type InteractionKind } from '../question-form/kinds';
import { QuestionPreview, learnerContent } from './QuestionPreview';

// Words that exist only on the solution side. If any of them is on the page, the preview leaked.
const SECRETS = ['SECRET-KEY-7', 'SECRET-EXPLANATION', 'SECRET-TRANSCRIPT', 'SECRET-SAMPLE'];
const POISON = { answerKey: { slots: [[SECRETS[0]]] }, explanation: SECRETS[1], transcript: SECRETS[2], sampleAnswer: SECRETS[3] };

const CONTENT: Record<InteractionKind, Record<string, unknown>> = {
  MCQ_SINGLE: { stem: 'Why?', options: [{ key: 'A', text: 'alpha' }, { key: 'B', text: 'beta' }] },
  MCQ_MULTI: { stem: 'Which two?', selectCount: 2, options: [{ key: 'A', text: 'alpha' }, { key: 'B', text: 'beta' }, { key: 'C', text: 'gamma' }] },
  GAP_TYPED: { title: 'Form', text: 'Name: [[1]]  Phone: [[2]]' },
  GAP_SELECT: { gapNumber: 131, stem: 'The ___ is late.', options: [{ key: 'A', text: 'train' }, { key: 'B', text: 'trains' }] },
  MATCHING: { instruction: 'Match', items: [{ text: 'Paragraph A' }, { text: 'Paragraph B' }], choices: [{ key: 'i', text: 'heading one' }, { key: 'ii', text: 'heading two' }] },
  TRUE_FALSE_NG: { statement: 'The sky is green.' },
  YES_NO_NG: { statement: 'The writer agrees.' },
  LABEL_DIAGRAM: { labelNumber: 3, marker: { x: 0.2, y: 0.4 }, choices: [{ key: 'A', text: 'library' }] },
  SHORT_ANSWER: { stem: 'What is the cause?' },
  ORDERING: { instruction: 'Put in order', items: [{ key: 'a', text: 'first thing' }, { key: 'b', text: 'second thing' }] },
  ESSAY: { prompt: 'Write about it.', minWords: 250 },
  SPEAKING_RESPONSE: { topic: 'A place you like', questions: ['Where is it?'], bulletPoints: ['why'] },
};

function view(kind: InteractionKind, content: Record<string, unknown> = { ...CONTENT[kind], ...POISON }) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <QuestionPreview kind={kind} content={content as never} />
    </QueryClientProvider>,
  );
}

describe('Preview.neverRendersAnswerKey', () => {
  for (const kind of INTERACTION_KINDS) {
    it(`${kind}: shows the learner's side and none of the solution side, even when the content carries it`, () => {
      const { container } = view(kind);
      const text = container.textContent ?? '';
      expect(text.length, 'something is shown').toBeGreaterThan(5);
      for (const s of SECRETS) expect(text, s).not.toContain(s);
      expect(container.innerHTML).not.toMatch(/SECRET-/);
      expect(screen.queryByText(/answer key|đáp án đúng|correct answer|sample answer|bài mẫu|transcript|lời thoại|giải thích|explanation/i)).not.toBeInTheDocument();
    });
  }

  it('drops the solution fields at any depth before rendering', () => {
    const c = learnerContent({ stem: 's', options: [{ key: 'A', text: 't', correct: true }], deep: { transcript: 'x', keep: 1 } } as never);
    expect(c).toEqual({ stem: 's', options: [{ key: 'A', text: 't' }], deep: { keep: 1 } });
  });

  it('shows the stem and the options of a multiple choice question', () => {
    view('MCQ_SINGLE');
    expect(screen.getByText('Why?')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /alpha/ })).toBeInTheDocument();
  });
});
