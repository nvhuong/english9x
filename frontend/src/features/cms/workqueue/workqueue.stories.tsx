import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '../../../app/stores/auth';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import type { QuestionSummary } from '../questions/types';
import WorkQueuePage from './WorkQueuePage';

const USER = '11111111-1111-1111-1111-111111111111';
const q = (questionId: string, status: QuestionSummary['status'], examCode: string, title: string, createdBy: string): QuestionSummary => ({
  questionId, versionId: `v-${questionId}`, versionNo: 1, status, examCode, typeCode: 'TOEIC_P3_CONVERSATION', title, createdBy,
});

/** The answers are in the query cache, so the story needs no server. */
function Story({ empty = false }: { empty?: boolean }) {
  useAuthStore.setState({ accessToken: 'story', status: 'authenticated', user: { id: USER, email: 'an@example.test', displayName: 'An', emailVerified: true, locale: 'vi' } as never });
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'TOEIC' }, { role: 'REVIEWER', org: null, exam: 'IELTS' }] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const seed = (exam: string, status: string, items: QuestionSummary[]) => client.setQueryData(ctxKey(null, 'cms', 'questions', 'queue', USER, exam, status), empty ? [] : items);
  seed('TOEIC', 'DRAFT', [q('1', 'DRAFT', 'TOEIC', 'Part 3: shipment call', USER)]);
  seed('TOEIC', 'CHANGES_REQUESTED', [q('2', 'CHANGES_REQUESTED', 'TOEIC', 'Part 5: tense', USER)]);
  seed('IELTS', 'IN_REVIEW', [q('3', 'IN_REVIEW', 'IELTS', 'Reading: headings', 'someone-else')]);
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <WorkQueuePage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const meta = { title: 'Features/CMS work queue', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const ThreeQueues: S = { render: () => <Story /> };
export const Empty: S = { render: () => <Story empty /> };
