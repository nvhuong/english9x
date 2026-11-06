import type { Meta, StoryObj } from '@storybook/react-vite';
import { Button } from './Button';
import { ToastProvider, useToast, type ToastTone } from './Toast';

function Demo({ tone }: { tone: ToastTone }) {
  const { toast } = useToast();
  return <Button onClick={() => toast({ title: 'Đã lưu bản nháp', description: 'Bạn có thể tiếp tục chỉnh sửa.', tone })}>Hiện thông báo</Button>;
}

const meta = {
  title: 'Design/Toast',
  component: Demo,
  decorators: [(Story) => <ToastProvider><Story /></ToastProvider>],
} satisfies Meta<typeof Demo>;
export default meta;
type S = StoryObj<typeof meta>;

export const Info: S = { args: { tone: 'info' } };
export const Success: S = { args: { tone: 'success' } };
export const Danger: S = { args: { tone: 'danger' } };
