import type { Meta, StoryObj } from '@storybook/react-vite';
import { Badge } from './Badge';

const meta = { title: 'Design/Badge', component: Badge } satisfies Meta<typeof Badge>;
export default meta;
export const AllTones: StoryObj<typeof meta> = {
  render: () => (
    <div className="flex flex-wrap gap-2">
      <Badge>Nháp</Badge>
      <Badge tone="info">Đang soạn</Badge>
      <Badge tone="success">Đã xuất bản</Badge>
      <Badge tone="warning">Chờ duyệt</Badge>
      <Badge tone="danger">Bị từ chối</Badge>
      <Badge tone="accent">Mới</Badge>
    </div>
  ),
};
