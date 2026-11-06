import type { Meta, StoryObj } from '@storybook/react-vite';
import { Highlighter } from './Highlighter';

const text = `Mỗi buổi sáng, chợ nổi trên sông bắt đầu từ rất sớm. Những chiếc thuyền chất đầy trái cây neo cạnh nhau, người bán treo mẫu hàng lên một cây sào dài để khách nhìn từ xa.

Theo nhiều người lớn tuổi, cách buôn bán này tồn tại vì đường bộ ngày trước rất khó đi. Ngày nay, dù cầu đường đã tốt hơn, chợ vẫn giữ được nét riêng nhờ khách du lịch.`;
const meta = { title: 'Exam/Highlighter', component: Highlighter, args: { text, defaultHighlights: [[3, 6]] } } satisfies Meta<typeof Highlighter>;
export default meta;
export const ReadingPassage: StoryObj<typeof meta> = {};
