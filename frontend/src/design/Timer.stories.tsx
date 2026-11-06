import type { Meta, StoryObj } from '@storybook/react-vite';
import { Timer } from './Timer';

// Fixed clock so stories are deterministic: "now" is always T0.
const T0 = Date.UTC(2026, 10, 7, 9, 0, 0);
const now = () => T0;
const meta = { title: 'Exam/Timer', component: Timer, args: { now }, decorators: [(Story) => <div data-surface="exam" className="bg-paper p-4"><Story /></div>] } satisfies Meta<typeof Timer>;
export default meta;
type S = StoryObj<typeof meta>;

export const Plenty: S = { args: { deadline: T0 + 45 * 60_000 } };
export const FiveMinutes: S = { args: { deadline: T0 + 4 * 60_000 + 30_000 } };
export const OneMinute: S = { args: { deadline: T0 + 45_000 } };
export const Expired: S = { args: { deadline: T0 - 1000 } };
