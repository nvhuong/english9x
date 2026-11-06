import type { Meta, StoryObj } from '@storybook/react-vite';
import { Tabs } from './Tabs';

const meta = {
  title: 'Design/Tabs',
  component: Tabs,
  args: {
    label: 'Nội dung bài học',
    items: [
      { value: 'read', label: 'Bài đọc', content: <p className="prose-reading">Bài đọc về nhịp sống ở một thị trấn ven biển.</p> },
      { value: 'vocab', label: 'Từ vựng', content: <p>Danh sách 12 từ cần nhớ.</p> },
      { value: 'practice', label: 'Luyện tập', content: <p>8 câu hỏi trắc nghiệm.</p> },
    ],
  },
} satisfies Meta<typeof Tabs>;
export default meta;
export const Default: StoryObj<typeof meta> = {};
