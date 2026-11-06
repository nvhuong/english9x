import type { Meta, StoryObj } from '@storybook/react-vite';
import { AudioPlayer } from './AudioPlayer';

const meta = { title: 'Exam/AudioPlayer', component: AudioPlayer, args: { src: '', autoStart: false } } satisfies Meta<typeof AudioPlayer>;
export default meta;
export const BeforeStart: StoryObj<typeof meta> = {};
