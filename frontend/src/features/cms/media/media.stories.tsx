import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { MemoryRouter } from 'react-router-dom';
import { publicKey } from '../../../app/stores/context';
import { MediaUploader } from './MediaUploader';
import { LIMITS } from './testUtils';

// The limits are in the query cache, so the story needs no server.
function seeded() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(publicKey('cms', 'media', 'limits'), LIMITS);
  return client;
}

const meta = { title: 'Features/CMS media', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const Uploader: S = {
  render: () => (
    <QueryClientProvider client={seeded()}>
      <MemoryRouter>
        <MediaUploader examCode="IELTS" />
      </MemoryRouter>
    </QueryClientProvider>
  ),
};
