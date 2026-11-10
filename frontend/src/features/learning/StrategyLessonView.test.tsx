import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Block } from '../cms/lesson-editor/types';
import { StrategyLessonView, type EmbeddedQuestionData } from './StrategyLessonView';

const QV = '8a1f0c5e-6b0e-4d0e-9a52-1f3a5c7e9b21';
const QUESTION: EmbeddedQuestionData = {
  versionId: QV,
  typeCode: 'TOEIC_P5_INCOMPLETE_SENTENCE',
  content: {
    stem: 'She ___ to work every day.',
    options: [
      { key: 'A', text: 'go' },
      { key: 'B', text: 'goes' },
    ],
  },
};
const src = (kind: string, id: string) => `https://files.test/${kind}/${id}`;

function view(blocks: Block[], extra: Partial<React.ComponentProps<typeof StrategyLessonView>> = {}) {
  return render(<StrategyLessonView title="Bài mẫu" blocks={blocks} mediaSrc={src} questions={{ [QV]: QUESTION }} {...extra} />);
}

describe('StrategyLessonView: every block type', () => {
  it('heading and paragraph are text, never markup', () => {
    view([
      { type: 'heading', level: 3, text: 'Đọc tiêu đề trước' },
      { type: 'paragraph', text: '<img src=x onerror=alert(1)> đọc lướt' },
    ]);
    expect(screen.getByRole('heading', { level: 1, name: 'Bài mẫu' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Đọc tiêu đề trước' })).toBeInTheDocument();
    expect(screen.getByText(/onerror=alert/)).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });

  it('table has a header row and the cells', () => {
    view([{ type: 'table', header: ['Bước', 'Việc làm'], rows: [['1', 'Đọc lướt']], caption: 'Các bước' }]);
    const table = screen.getByRole('table', { name: 'Các bước' });
    expect(within(table).getByRole('columnheader', { name: 'Việc làm' })).toBeInTheDocument();
    expect(within(table).getByRole('cell', { name: 'Đọc lướt' })).toBeInTheDocument();
  });

  it('image, audio and uploaded video come from the signed links the page passes in; a file without a link is said so', () => {
    view([
      { type: 'image', imageMediaId: 'i1', alt: 'Sơ đồ', caption: 'Hình 1' },
      { type: 'audio', audioMediaId: 'a1', caption: 'Nghe thử' },
      { type: 'video-upload', videoMediaId: 'v1' },
      { type: 'image', imageMediaId: 'gone', alt: 'x' },
    ], { mediaSrc: (k, id) => (id === 'gone' ? undefined : src(k, id)) });
    expect(screen.getByRole('img', { name: 'Sơ đồ' })).toHaveAttribute('src', 'https://files.test/IMAGE/i1');
    expect(screen.getByText('Hình 1')).toBeInTheDocument();
    expect(screen.getByLabelText('Nghe thử')).toHaveAttribute('src', 'https://files.test/AUDIO/a1');
    expect(document.querySelector('video')).toHaveAttribute('src', 'https://files.test/VIDEO/v1');
    expect(screen.getByText('Tệp này chưa mở được.')).toBeInTheDocument();
  });

  it('video-embed frames only a rebuilt YouTube or Vimeo address', () => {
    view([
      { type: 'video-embed', provider: 'youtube', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5' },
      { type: 'video-embed', provider: 'vimeo', url: 'https://vimeo.com/76979871' },
      { type: 'video-embed', provider: 'youtube', url: 'https://evil.example/watch?v=dQw4w9WgXcQ' },
      { type: 'video-embed', provider: 'youtube', url: 'javascript:alert(1)' },
    ]);
    const frames = document.querySelectorAll('iframe');
    expect([...frames].map((f) => f.getAttribute('src'))).toEqual(['https://www.youtube.com/embed/dQw4w9WgXcQ', 'https://player.vimeo.com/video/76979871']);
    expect(screen.getAllByText('Video này không phát được ở đây.')).toHaveLength(2);
  });

  it('callout tip and trap are told apart by their label', () => {
    view([
      { type: 'callout', kind: 'tip', text: 'Gạch chân từ khoá.' },
      { type: 'callout', kind: 'trap', text: 'Đừng ghép theo một từ.' },
    ]);
    expect(within(screen.getByRole('complementary', { name: 'Mẹo' })).getByText('Gạch chân từ khoá.')).toBeInTheDocument();
    expect(within(screen.getByRole('complementary', { name: 'Bẫy' })).getByText('Đừng ghép theo một từ.')).toBeInTheDocument();
  });

  it('worked example lists its steps and keeps the result closed until asked', async () => {
    view([{ type: 'worked-example', title: 'Ví dụ 1', problem: 'Chọn tiêu đề?', steps: ['Tìm câu chủ đề.', 'So sánh.'], answer: 'Heading iv' }]);
    const ex = screen.getByRole('region', { name: 'Ví dụ giải: Ví dụ 1' });
    expect(within(ex).getAllByRole('listitem').map((l) => l.textContent)).toEqual(['Tìm câu chủ đề.', 'So sánh.']);
    expect(screen.queryByText(/Heading iv/)).toBeNull();
    await userEvent.click(within(ex).getByRole('button', { name: 'Xem kết quả' }));
    expect(screen.getByText(/Heading iv/)).toBeInTheDocument();
  });
});

describe('StrategyLessonView: the embedded question hides the answer until the learner answers', () => {
  const embedded: Block[] = [{ type: 'embedded-question', questionVersionId: QV }];

  it('shows the question and no verdict, answer or explanation before an answer', () => {
    view(embedded, { checkAnswer: vi.fn() });
    expect(screen.getByText('She ___ to work every day.')).toBeInTheDocument();
    expect(screen.queryByText(/Đáp án/)).toBeNull();
    expect(screen.queryByText(/Giải thích/)).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.getByRole('button', { name: 'Kiểm tra' })).toBeDisabled();
  });

  it('asks the server for the verdict and only then shows the answer and the explanation', async () => {
    const check = vi.fn().mockResolvedValue({ correct: false, correctAnswer: 'B', explanation: 'Chủ ngữ số ít.' });
    view(embedded, { checkAnswer: check });
    await userEvent.click(screen.getByRole('radio', { name: /go$/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Kiểm tra' }));
    expect(check).toHaveBeenCalledWith(QV, 'A');
    const verdict = await screen.findByRole('status');
    expect(within(verdict).getByText('Chưa đúng')).toBeInTheDocument();
    expect(within(verdict).getByText('Đáp án: B')).toBeInTheDocument();
    expect(within(verdict).getByText(/Chủ ngữ số ít/)).toBeInTheDocument();
    screen.getAllByRole('radio').forEach((r) => expect(r).toBeDisabled());
  });

  it('holds no answer data at all: the learner view type has nowhere to put one', () => {
    view(embedded, { checkAnswer: vi.fn() });
    expect(document.body.innerHTML).not.toMatch(/answerKey|slots|explanation|transcript/i);
  });

  it('says so when the question cannot be opened', () => {
    view(embedded, { questions: {} });
    expect(screen.getByText('Câu hỏi này chưa mở được.')).toBeInTheDocument();
  });
});
