import type { Meta, StoryObj } from '@storybook/react-vite';
import { AuditFilterForm } from './AuditFilterForm';
import { AuditLogView } from './AuditLogView';
import { NO_FILTERS, type AuditEntry } from './queries';

const entries: AuditEntry[] = [
  {
    id: '00000000-0000-0000-0000-000000000003', seq: 3, at: '2026-11-07T03:00:00Z', actorKind: 'USER', actorId: 'a1', actorName: 'Nguyễn Văn An', actorEmail: 'an@example.com',
    orgId: undefined, orgName: undefined, action: 'review.approve', targetType: 'review', targetId: '9b2f6c1e-3f4a-4b57-8f07-2c0f6f1c9a10',
    detail: { type: 'question', versionId: '5d1c7a88-6a51-4d3e-9c44-1e6b0b7d5f22', examCode: 'IELTS' },
  },
  {
    id: '00000000-0000-0000-0000-000000000002', seq: 2, at: '2026-11-07T02:30:00Z', actorKind: 'USER', actorId: 'a2', actorName: 'Trần Thị Bình', actorEmail: 'binh@example.com',
    orgId: 'o1', orgName: 'Trung tâm Ánh Dương', action: 'role.change', targetType: 'user', targetId: 'c3a7d9e0-1b2c-4d5e-8f60-71829304a5b6',
    detail: { orgId: 'o1', before: ['LEARNER'], after: ['AUTHOR/IELTS', 'LEARNER'] },
  },
  {
    id: '00000000-0000-0000-0000-000000000001', seq: 1, at: '2026-11-06T09:00:00Z', actorKind: 'OPERATOR', actorId: undefined, actorName: undefined, actorEmail: undefined,
    orgId: undefined, orgName: undefined, action: 'sys-admin.grant', targetType: 'user', targetId: 'a1', detail: { via: 'scripts/grant-sys-admin.sh' },
  },
];

const meta = { title: 'Features/Audit log', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const Records: S = { render: () => <AuditLogView entries={entries} /> };
export const Filters: S = {
  render: () => <AuditFilterForm value={NO_FILTERS} actions={['invitation.send', 'org.create', 'review.approve', 'role.change']} onApply={() => {}} />,
};
