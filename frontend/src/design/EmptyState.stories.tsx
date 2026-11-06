import type { Meta, StoryObj } from '@storybook/react-vite';
import { Button } from './Button';
import { EmptyState } from './EmptyState';

const meta = {
  title: 'Design/EmptyState',
  component: EmptyState,
  args: { title: 'Chưa có bài làm nào', description: 'Hãy làm bài thi thử đầu tiên để xem điểm yếu của bạn.', icon: '📝', action: <Button>Làm bài thi thử</Button> },
} satisfies Meta<typeof EmptyState>;
export default meta;
export const Default: StoryObj<typeof meta> = {};
