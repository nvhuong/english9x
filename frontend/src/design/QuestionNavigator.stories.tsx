import type { Meta, StoryObj } from '@storybook/react-vite';
import { QuestionNavigator } from './QuestionNavigator';

const questions = Array.from({ length: 40 }, (_, i) => ({ id: `q${i + 1}`, number: i + 1, answered: i % 3 !== 2 && i < 25, flagged: i === 7 || i === 19 }));
const meta = { title: 'Exam/QuestionNavigator', component: QuestionNavigator, args: { questions, currentId: 'q9', onSelect: () => {} } } satisfies Meta<typeof QuestionNavigator>;
export default meta;
export const Forty: StoryObj<typeof meta> = {};
