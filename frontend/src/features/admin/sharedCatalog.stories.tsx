import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { useAuthStore } from '../../app/stores/auth';
import { ctxKey, useContextStore } from '../../app/stores/context';
import { useRoleStore } from '../../app/stores/roles';
import { SharedCatalogPanel } from '../cms/course-builder/SharedCatalogPanel';
import type { CourseVersion } from '../cms/course-builder/types';
import SharedCatalogQueue from './SharedCatalogQueue';
import type { SharedCatalogQueueItem } from './sharedCatalog';

const OWNER = 'aaaaaaaa-0000-0000-0000-00000000000a';
const items: SharedCatalogQueueItem[] = [
  { courseId: 'c1', versionId: 'v1', code: 'TOEIC1', examCode: 'TOEIC', versionNo: 2, title: 'Ngữ pháp TOEIC', authorDisplay: 'Cô Lan', ownerOrgId: 'bbbbbbbb-0000-0000-0000-00000000000b', ownerName: 'Trung tâm A', sharedStatus: 'PENDING' },
  { courseId: 'c2', versionId: 'v2', code: 'TOEIC2', examCode: 'TOEIC', versionNo: 1, title: 'Từ vựng TOEIC', authorDisplay: 'Thầy Minh', ownerOrgId: OWNER, ownerName: 'Trung tâm của tôi', sharedStatus: 'PENDING' },
];

function Queue({ empty = false }: { empty?: boolean }) {
  useAuthStore.setState({ accessToken: 'story', status: 'authenticated', user: { id: '1', email: 'an@example.test', displayName: 'An', emailVerified: true, locale: 'vi' } as never });
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: [{ role: 'REVIEWER', org: null, exam: 'TOEIC' }, { role: 'AUTHOR', org: OWNER, exam: 'TOEIC' }] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(ctxKey(null, 'shared-catalog', 'queue'), empty ? [] : items);
  return (
    <QueryClientProvider client={client}>
      <SharedCatalogQueue />
    </QueryClientProvider>
  );
}

function Panel({ state }: { state: CourseVersion['sharedStatus'] }) {
  useContextStore.setState({ orgId: OWNER });
  useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: OWNER, exam: 'TOEIC' }] });
  const client = new QueryClient();
  return (
    <QueryClientProvider client={client}>
      <SharedCatalogPanel version={{ courseId: 'c1', status: 'PUBLISHED', examCode: 'TOEIC', sharedStatus: state } as CourseVersion} />
    </QueryClientProvider>
  );
}

const meta = { title: 'Features/Shared catalogue (F21)', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const ReviewQueue: S = { render: () => <Queue /> };
export const EmptyQueue: S = { render: () => <Queue empty /> };
export const OfferNotYet: S = { render: () => <Panel state="NONE" /> };
export const OfferPending: S = { render: () => <Panel state="PENDING" /> };
export const OfferRejected: S = { render: () => <Panel state="REJECTED" /> };
export const OfferApproved: S = { render: () => <Panel state="APPROVED" /> };
