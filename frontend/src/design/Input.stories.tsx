import type { Meta, StoryObj } from '@storybook/react-vite';
import { Input } from './Input';

const meta = { title: 'Design/Input', component: Input, args: { label: 'Họ và tên' } } satisfies Meta<typeof Input>;
export default meta;
type S = StoryObj<typeof meta>;

export const Default: S = {};
export const WithHint: S = { args: { label: 'Email', hint: 'Chúng tôi dùng email này để gửi kết quả.', type: 'email' } };
export const Required: S = { args: { required: true } };
export const WithError: S = { args: { label: 'Email', defaultValue: 'abc', error: 'Email không hợp lệ' } };
export const Disabled: S = { args: { disabled: true, defaultValue: 'Nguyễn Văn A' } };
