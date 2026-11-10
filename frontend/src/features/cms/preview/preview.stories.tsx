import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { InteractionKind } from '../question-form/kinds';
import { QuestionPreview } from './QuestionPreview';

const CONTENT: Record<string, { kind: InteractionKind; content: Record<string, unknown> }> = {
  Mcq: { kind: 'MCQ_SINGLE', content: { stem: 'What are the speakers discussing?', options: [{ key: 'A', text: 'A delayed shipment' }, { key: 'B', text: 'A new schedule' }, { key: 'C', text: 'A budget' }, { key: 'D', text: 'A hiring plan' }] } },
  Gaps: { kind: 'GAP_TYPED', content: { title: 'Booking form', text: 'Name: [[1]]  Phone: [[2]]' } },
  Matching: { kind: 'MATCHING', content: { instruction: 'Match each paragraph with a heading.', items: [{ text: 'Paragraph A' }, { text: 'Paragraph B' }], choices: [{ key: 'i', text: 'A first heading' }, { key: 'ii', text: 'A second heading' }] } },
  Essay: { kind: 'ESSAY', content: { prompt: 'Some people think cities should ban cars. Discuss both views.', minWords: 250 } },
  Ordering: { kind: 'ORDERING', content: { instruction: 'Put the steps in order.', items: [{ key: 'a', text: 'Mix the flour' }, { key: 'b', text: 'Bake the bread' }] } },
};

function Story({ name }: { name: keyof typeof CONTENT }) {
  const c = CONTENT[name]!;
  return (
    <QueryClientProvider client={new QueryClient()}>
      <QuestionPreview kind={c.kind} content={c.content as never} />
    </QueryClientProvider>
  );
}

const meta = { title: 'Features/CMS preview as learner', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const MultipleChoice: S = { render: () => <Story name="Mcq" /> };
export const GapFill: S = { render: () => <Story name="Gaps" /> };
export const Matching: S = { render: () => <Story name="Matching" /> };
export const Essay: S = { render: () => <Story name="Essay" /> };
export const Ordering: S = { render: () => <Story name="Ordering" /> };
