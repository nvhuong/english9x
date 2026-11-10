import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { publicKey } from '../../../app/stores/context';
import { LIMITS } from '../media/testUtils';
import { QuestionForm } from './QuestionForm';
import { modelOf, type FormModel } from './schema';
import { QUESTION, TOEIC_P3 } from './testUtils';
import type { FieldIssue } from './problems';

// The upload limits (read by the media widget's uploader) are in the query cache, so the stories need no server.
function seeded() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(publicKey('cms', 'media', 'limits'), LIMITS);
  return client;
}

function Story({ issues = [], locked = false }: { issues?: FieldIssue[]; locked?: boolean }) {
  const [model, setModel] = useState<FormModel>(modelOf(QUESTION));
  return (
    <QueryClientProvider client={seeded()}>
      <MemoryRouter>
        <QuestionForm type={TOEIC_P3} model={model} onChange={setModel} onSave={() => {}} issues={issues} locked={locked} saveLabel="Lưu bản nháp" />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const meta = { title: 'Features/CMS questions', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const FormOfAType: S = { render: () => <Story /> };
export const WithServerErrors: S = {
  render: () => (
    <Story
      issues={[
        { field: 'content.options[2].key', message: 'does not match the pattern' },
        { field: 'content.stem', message: 'is required' },
      ]}
    />
  ),
};
export const LockedUnderReview: S = { render: () => <Story locked /> };
