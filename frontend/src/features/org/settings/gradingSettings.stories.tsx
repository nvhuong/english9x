import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import { Settings } from './GradingSettingsPage';

const ORG = 'aaaaaaaa-0000-0000-0000-00000000000a';

/** The answer is in the query cache, so the story needs no server. */
function Story({ enterprise, grader, mode }: { enterprise: boolean; grader: 'CENTER' | 'ENGLISH9X'; mode?: 'AI' | 'TEACHER' | 'BOTH' }) {
  useContextStore.setState({ orgId: ORG });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(ctxKey(ORG, 'org', ORG, 'grading-settings'), { grader, mode });
  return (
    <QueryClientProvider client={client}>
      <Settings orgId={ORG} enterprise={enterprise} />
    </QueryClientProvider>
  );
}

const meta = { title: 'Features/Grading settings (F50)', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const Center: S = { render: () => <Story enterprise={false} grader="CENTER" mode="BOTH" /> };
export const EnterpriseLocked: S = { render: () => <Story enterprise grader="ENGLISH9X" mode="AI" /> };
