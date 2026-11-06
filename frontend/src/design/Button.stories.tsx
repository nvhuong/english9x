import type { Meta, StoryObj } from '@storybook/react-vite';
import { Button } from './Button';

const meta = { title: 'Design/Button', component: Button, args: { children: 'Bắt đầu học' } } satisfies Meta<typeof Button>;
export default meta;
type S = StoryObj<typeof meta>;

export const Primary: S = {};
export const Accent: S = { args: { variant: 'accent' } };
export const Secondary: S = { args: { variant: 'secondary' } };
export const Ghost: S = { args: { variant: 'ghost' } };
export const Danger: S = { args: { variant: 'danger', children: 'Xoá bài' } };
export const Loading: S = { args: { loading: true, children: 'Đang lưu' } };
export const Disabled: S = { args: { disabled: true } };
export const AllSizes: S = {
  render: () => (
    <div className="flex flex-wrap items-center gap-3">
      <Button size="sm">Nhỏ</Button>
      <Button size="md">Vừa</Button>
      <Button size="lg">Lớn</Button>
    </div>
  ),
};
