import type { Meta, StoryObj } from '@storybook/react-vite';
import { Select } from './Select';

const options = [
  { value: 'ac', label: 'IELTS Academic' },
  { value: 'gt', label: 'IELTS General Training' },
  { value: 'toeic', label: 'TOEIC Listening & Reading' },
];
const meta = { title: 'Design/Select', component: Select, args: { label: 'Kỳ thi', options } } satisfies Meta<typeof Select>;
export default meta;
type S = StoryObj<typeof meta>;

export const Default: S = {};
export const WithValue: S = { args: { defaultValue: 'gt' } };
export const WithError: S = { args: { error: 'Hãy chọn một kỳ thi' } };
