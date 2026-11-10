import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { QuestionForm } from './QuestionForm';
import { modelOf, type FormModel } from './schema';
import { QUESTION, TOEIC_P3 } from './testUtils';
import type { FieldIssue } from './problems';

function Harness({ initial = modelOf(QUESTION), issues = [], locked = false, onSave = vi.fn() }: { initial?: FormModel; issues?: FieldIssue[]; locked?: boolean; onSave?: (m: FormModel) => void }) {
  const [model, setModel] = useState(initial);
  return (
    <QueryClientProvider client={new QueryClient()}>
      <QuestionForm type={TOEIC_P3} model={model} onChange={setModel} onSave={onSave} issues={issues} locked={locked} saveLabel="Lưu bản nháp" />
    </QueryClientProvider>
  );
}

describe('QuestionForm', () => {
  it('builds the form from the schemas of the type, in Vietnamese, with the answer key on its own section', () => {
    render(<Harness />);
    expect(screen.getByRole('group', { name: 'Nội dung câu hỏi' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: /Đáp án và phần giải/ })).toBeInTheDocument();
    expect(screen.getByLabelText(/^Câu hỏi/)).toHaveValue('What are the speakers discussing?');
    expect(screen.getByLabelText('Lời thoại (tài liệu xem lại sau khi nộp)')).toHaveValue('Script of the recording.');
    expect(screen.getByLabelText('Âm thanh (mã tệp)')).toHaveValue('');
    expect(screen.getByLabelText('Điểm')).toHaveValue(1);
  });

  it('saves what was typed without checking it itself: the server holds the rules', async () => {
    const onSave = vi.fn();
    render(<Harness initial={{ content: {}, answerKey: {}, explanation: '', points: 1 }} onSave={onSave} />);
    await userEvent.type(screen.getByLabelText(/^Câu hỏi/), 'New?');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onSave.mock.calls[0]![0]).toMatchObject({ content: { stem: 'New?' }, points: 1 });
  });

  it('shows the server\'s errors by JSON path, next to the field and in a list that is announced', async () => {
    render(<Harness issues={[{ field: 'content.options[2].key', message: 'does not match the pattern' }, { field: 'content.stem', message: 'is required' }]} />);
    const summary = screen.getByRole('alert');
    expect(within(summary).getByText('content.options[2].key')).toBeInTheDocument();
    expect(within(summary).getByText(/does not match the pattern/)).toBeInTheDocument();
    expect(screen.getAllByText(/is required/).length).toBeGreaterThanOrEqual(2); // in the list and beside the field
    expect(screen.getByLabelText(/^Câu hỏi/)).toHaveAccessibleDescription(/is required/);
  });

  it('edits the same two documents as JSON, and refuses text that is not JSON', async () => {
    const onSave = vi.fn();
    render(<Harness onSave={onSave} />);
    await userEvent.click(screen.getByRole('button', { name: 'Soạn bằng JSON' }));
    const box = screen.getByLabelText(/Câu hỏi dạng JSON/);
    expect(JSON.parse((box as HTMLTextAreaElement).value)).toMatchObject({ content: { stem: 'What are the speakers discussing?' }, answerKey: { slots: [['B']] } });
    await userEvent.clear(box);
    await userEvent.type(box, 'not json');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(screen.getByText('Văn bản này không phải JSON hợp lệ.')).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
    await userEvent.clear(box);
    await userEvent.click(box);
    await userEvent.paste('{"content":{"stem":"From JSON"},"answerKey":{"slots":[["A"]]},"points":2}');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(onSave).toHaveBeenCalledWith({ content: { stem: 'From JSON' }, answerKey: { slots: [['A']] }, explanation: '', points: 2 });
  });

  it('is read-only when the version is locked: no save, nothing to type into', () => {
    render(<Harness locked />);
    expect(screen.queryByRole('button', { name: 'Lưu bản nháp' })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Câu hỏi/)).toBeDisabled();
  });
});
