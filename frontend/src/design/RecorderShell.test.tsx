import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { RecorderShell, type RecorderPhase } from './RecorderShell';

describe('RecorderShell phases', () => {
  it('mic-check: meter reflects the level, test button works, ok message appears with signal', async () => {
    const onMicTest = vi.fn();
    const { rerender } = render(<RecorderShell phase="mic-check" micLevel={0} onMicTest={onMicTest} />);
    expect(screen.getByRole('group', { name: 'Ghi âm bài nói' })).toHaveAttribute('data-phase', 'mic-check');
    expect(screen.getByRole('meter', { name: 'Mức âm micro' })).toHaveAttribute('aria-valuenow', '0');
    expect(screen.getByRole('status')).toHaveTextContent('Hãy nói vài câu');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Kiểm tra micro' }));
    expect(onMicTest).toHaveBeenCalled();
    rerender(<RecorderShell phase="mic-check" micLevel={0.6} />);
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '60');
    expect(screen.getByRole('status')).toHaveTextContent('Micro hoạt động tốt');
  });

  it('denied: explains and offers retry', async () => {
    const onRetry = vi.fn();
    render(<RecorderShell phase="denied" onRetry={onRetry} />);
    expect(screen.getByRole('status')).toHaveTextContent('Chưa được cấp quyền dùng micro');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(onRetry).toHaveBeenCalled();
  });

  it('ready: Begin starts', async () => {
    const onBegin = vi.fn();
    render(<RecorderShell phase="ready" onBegin={onBegin} />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Bắt đầu' }));
    expect(onBegin).toHaveBeenCalled();
  });

  it('preparing: countdown and progress, no stop button', () => {
    render(<RecorderShell phase="preparing" secondsLeft={30} totalSeconds={60} />);
    expect(screen.getByRole('heading', { name: 'Thời gian chuẩn bị' })).toBeInTheDocument();
    expect(screen.getByText('30 giây')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '50');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('recording: level meter, countdown and a stop button', async () => {
    const onStop = vi.fn();
    render(<RecorderShell phase="recording" secondsLeft={45} totalSeconds={120} micLevel={0.3} onStop={onStop} />);
    expect(screen.getByRole('heading', { name: 'Đang ghi âm' })).toBeInTheDocument();
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '30');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Dừng ghi âm' }));
    expect(onStop).toHaveBeenCalled();
  });

  it.each([
    ['uploading', 'Đang tải bản ghi lên'],
    ['done', 'Đã gửi bản ghi'],
    ['error', 'Không gửi được bản ghi'],
  ] as Array<[RecorderPhase, string]>)('%s state announces its status', (phase, text) => {
    render(<RecorderShell phase={phase} />);
    expect(screen.getByRole('status')).toHaveTextContent(text);
  });
});
