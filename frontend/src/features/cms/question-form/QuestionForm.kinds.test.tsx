import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { QuestionForm } from '../questions/QuestionForm';
import { emptyModel, type FormModel } from '../questions/schema';
import type { QuestionTypeForm } from '../questions/types';
import { INTERACTION_KINDS, kindOf } from './kinds';
import { allKindTypes, typeOfKind } from './testUtils';

function Harness({ type, initial = emptyModel(), onSave = vi.fn() }: { type: QuestionTypeForm; initial?: FormModel; onSave?: (m: FormModel) => void }) {
  const [model, setModel] = useState(initial);
  return (
    <QueryClientProvider client={new QueryClient()}>
      <QuestionForm type={type} model={model} onChange={setModel} onSave={onSave} issues={[]} saveLabel="Lưu bản nháp" />
    </QueryClientProvider>
  );
}

const propsOf = (schema: unknown) => Object.keys((schema as { properties: object }).properties);

describe('QuestionForm.rendersEveryInteractionKind', () => {
  it('has a type for each of the twelve kinds', () => {
    expect(allKindTypes().map((t) => kindOf(t))).toEqual([...INTERACTION_KINDS]);
    expect(INTERACTION_KINDS).toHaveLength(12);
  });

  for (const kind of INTERACTION_KINDS) {
    it(`${kind}: the content and the answer key each get their section and a field for every property of the schema`, () => {
      const type = typeOfKind(kind);
      const { container, unmount } = render(<Harness type={type} />);
      expect(screen.getByRole('group', { name: 'Nội dung câu hỏi' })).toBeInTheDocument();
      expect(screen.getByRole('group', { name: /Đáp án và phần giải/ })).toBeInTheDocument();
      for (const p of propsOf(type.contentSchema)) expect(container.querySelector(`[id^="root_content_${p}"]`), `content.${p}`).not.toBeNull();
      for (const p of propsOf(type.answerKeySchema)) expect(container.querySelector(`[id^="root_answerKey_${p}"]`), `answerKey.${p}`).not.toBeNull();
      expect(screen.getByRole('button', { name: 'Lưu bản nháp' })).toBeEnabled();
      unmount();
    });
  }

  it('edits a table of options row by row (a widget for lists of key and text)', async () => {
    const onSave = vi.fn();
    render(<Harness type={typeOfKind('MCQ_SINGLE')} initial={{ ...emptyModel(), content: { options: [{ key: 'A', text: 'one' }] } }} onSave={onSave} />);
    const table = () => screen.getByRole('table', { name: 'Các lựa chọn' });
    const cell = screen.getByRole('textbox', { name: 'Nội dung, dòng 1' });
    await userEvent.clear(cell);
    await userEvent.type(cell, 'uno'); // the same input keeps the focus while typing: the table is not rebuilt
    expect(cell).toHaveFocus();
    await userEvent.click(screen.getByRole('button', { name: 'Thêm dòng' }));
    expect(within(table()).getAllByRole('row')).toHaveLength(3); // header + 2
    await userEvent.type(within(table()).getByRole('textbox', { name: 'Ký hiệu, dòng 2' }), 'B');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(onSave.mock.calls[0]![0].content.options).toEqual([{ key: 'A', text: 'uno' }, { key: 'B' }]);
  });

  it('edits the accepted variants of a gap as one per line (a widget for the answer key variants)', async () => {
    const onSave = vi.fn();
    render(<Harness type={typeOfKind('SHORT_ANSWER')} initial={{ ...emptyModel(), answerKey: { slots: [['pollution', 'air pollution']] } }} onSave={onSave} />);
    const box = screen.getByRole('textbox', { name: 'Cách viết chấp nhận, chỗ trống 1' });
    expect(box).toHaveValue('pollution\nair pollution');
    await userEvent.type(box, '\nsmog');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(onSave.mock.calls[0]![0].answerKey.slots).toEqual([['pollution', 'air pollution', 'smog']]);
  });
});
