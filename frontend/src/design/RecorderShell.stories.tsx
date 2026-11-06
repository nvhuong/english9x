import type { Meta, StoryObj } from '@storybook/react-vite';
import { RecorderShell } from './RecorderShell';

const meta = { title: 'Exam/RecorderShell', component: RecorderShell, args: { phase: 'mic-check' } } satisfies Meta<typeof RecorderShell>;
export default meta;
type S = StoryObj<typeof meta>;

export const MicCheck: S = { args: { phase: 'mic-check', micLevel: 0.4 } };
export const MicDenied: S = { args: { phase: 'denied' } };
export const Ready: S = { args: { phase: 'ready' } };
export const Preparing: S = { args: { phase: 'preparing', secondsLeft: 40, totalSeconds: 60 } };
export const Recording: S = { args: { phase: 'recording', secondsLeft: 75, totalSeconds: 120, micLevel: 0.55 } };
export const Uploading: S = { args: { phase: 'uploading' } };
export const Done: S = { args: { phase: 'done' } };
export const UploadError: S = { args: { phase: 'error' } };
