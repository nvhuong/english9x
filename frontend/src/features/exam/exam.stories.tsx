import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { QuestionInput } from './QuestionInput';
import { SaveStatus } from './SaveStatus';
import { SectionTimer } from './SectionTimer';
import { ReadingSplitView } from './ReadingSplitView';
import { WritingEditor } from './WritingEditor';
import type { Json } from './types';

const meta = { title: 'Exam/Room', decorators: [(Story) => <div data-surface="exam" className="bg-paper p-4"><Story /></div>] } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

const choices = [{ key: 'A', text: 'one' }, { key: 'B', text: 'two' }, { key: 'C', text: 'three' }, { key: 'D', text: 'four' }];

export const SectionClock: S = { render: () => <SectionTimer endsAt="2026-11-10T09:40:00Z" serverNow="2026-11-10T09:30:00Z" perfNow={() => 0} /> };

export const Saving: S = {
  render: () => (
    <div className="flex flex-col gap-2">
      <SaveStatus state="saved" pending={0} />
      <SaveStatus state="saving" pending={0} />
      <SaveStatus state="offline" pending={3} />
    </div>
  ),
};

function Q({ kind, content, initial }: { kind: Parameters<typeof QuestionInput>[0]['kind']; content: Record<string, Json>; initial?: Json }) {
  const [v, setV] = useState<Json | undefined>(initial);
  return <QuestionInput kind={kind} content={content} value={v} onChange={setV} number={7} />;
}

export const MultipleChoice: S = { render: () => <Q kind="MCQ_SINGLE" content={{ stem: 'What is the purpose of the email?', options: choices }} /> };
export const MultiSelect: S = { render: () => <Q kind="MCQ_MULTI" content={{ stem: 'Which TWO things?', selectCount: 2, options: choices }} /> };
export const TrueFalse: S = { render: () => <Q kind="TRUE_FALSE_NG" content={{ statement: 'The bridge was finished in 1932.' }} /> };
export const Gaps: S = { render: () => <Q kind="GAP_TYPED" content={{ text: 'Name: [[1]]  Phone: [[2]]' }} /> };
export const Matching: S = {
  render: () => <Q kind="MATCHING" content={{ items: [{ text: 'Paragraph A' }, { text: 'Paragraph B' }], choices: [{ key: 'i', text: 'heading one' }, { key: 'ii', text: 'heading two' }] }} />,
};
export const Ordering: S = { render: () => <Q kind="ORDERING" content={{ items: [{ key: 'a', text: 'first' }, { key: 'b', text: 'second' }] }} /> };
export const ShortAnswer: S = { render: () => <Q kind="SHORT_ANSWER" content={{ stem: 'What is the main cause?' }} /> };
function EssayDemo() {
  const [v, setV] = useState('Some words in an essay.');
  return <WritingEditor value={v} onChange={setV} minWords={150} />;
}
export const Essay: S = { render: () => <EssayDemo /> };
export const Reading: S = {
  render: () => (
    <ReadingSplitView passage={'First paragraph of the passage.\n\nSecond paragraph.'} title="A passage">
      <Q kind="TRUE_FALSE_NG" content={{ statement: 'The first paragraph exists.' }} />
    </ReadingSplitView>
  ),
};
