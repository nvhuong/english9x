import type { Meta, StoryObj } from '@storybook/react-vite';
import { Button } from './Button';
import { Dialog } from './Dialog';

const meta = { title: 'Design/Dialog', component: Dialog } satisfies Meta<typeof Dialog>;
export default meta;
type S = StoryObj<typeof meta>;

export const Closed: S = { args: { trigger: <Button>Xoá bài học</Button>, title: 'Xoá bài học?', description: 'Bài học sẽ chuyển vào thùng rác 30 ngày.' } };
export const Open: S = {
  args: {
    open: true,
    title: 'Nộp bài thi?',
    description: 'Bạn còn 3 câu chưa trả lời. Sau khi nộp sẽ không sửa được.',
    footer: (
      <>
        <Button variant="secondary">Làm tiếp</Button>
        <Button variant="accent">Nộp bài</Button>
      </>
    ),
    children: <p>Thời gian còn lại: 12 phút.</p>,
  },
};
