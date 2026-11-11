import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { QuestionInput } from './QuestionInput';
import { kindOfQuestion } from './kinds';
import type { InteractionKind, Json, JsonObject } from './types';

const opts = [{ key: 'A', text: 'one' }, { key: 'B', text: 'two' }, { key: 'C', text: 'three' }];
function run(kind: InteractionKind, content: JsonObject, value?: Json) {
  const onChange = vi.fn();
  render(<QuestionInput kind={kind} content={content} value={value} onChange={onChange} number={5} />);
  return onChange;
}

describe('QuestionInput: the answer shapes the scorer reads', () => {
  it('MCQ_SINGLE / GAP_SELECT: the key as text', () => {
    const f = run('MCQ_SINGLE', { stem: 'Which?', options: opts });
    fireEvent.click(screen.getByLabelText(/two/));
    expect(f).toHaveBeenCalledWith('B');
  });
  it('MCQ_MULTI: a list of keys, at most selectCount', () => {
    const f = run('MCQ_MULTI', { stem: 'Which two?', selectCount: 2, options: opts }, ['A', 'B']);
    fireEvent.click(screen.getByLabelText(/three/));
    expect(f).not.toHaveBeenCalled(); // a third one is refused
    fireEvent.click(screen.getByLabelText(/one/));
    expect(f).toHaveBeenCalledWith(['B']);
  });
  it('TRUE_FALSE_NG and YES_NO_NG', () => {
    const f = run('TRUE_FALSE_NG', { statement: 'It rained.' });
    fireEvent.click(screen.getByLabelText('NOT GIVEN'));
    expect(f).toHaveBeenCalledWith('NOT GIVEN');
    const g = run('YES_NO_NG', { statement: 'He agrees.' });
    fireEvent.click(screen.getAllByLabelText('YES')[0]!);
    expect(g).toHaveBeenCalledWith('YES');
  });
  it('GAP_TYPED: one text per [[n]], in order', () => {
    const f = run('GAP_TYPED', { text: 'Name: [[1]] Phone: [[2]]' }, ['Smith', '']);
    fireEvent.change(screen.getByLabelText(/2/), { target: { value: '0123' } });
    expect(f).toHaveBeenCalledWith(['Smith', '0123']);
  });
  it('MATCHING: the chosen key of each item', () => {
    const f = run('MATCHING', { items: [{ text: 'Para A' }, { text: 'Para B' }], choices: [{ key: 'i', text: 'h1' }, { key: 'ii', text: 'h2' }] }, ['i', '']);
    fireEvent.change(screen.getAllByRole('combobox')[1]!, { target: { value: 'ii' } });
    expect(f).toHaveBeenCalledWith(['i', 'ii']);
  });
  it('LABEL_DIAGRAM and SHORT_ANSWER: text', () => {
    const f = run('LABEL_DIAGRAM', { labelNumber: 3 });
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'library' } });
    expect(f).toHaveBeenCalledWith('library');
    const g = run('SHORT_ANSWER', { stem: 'Cause?' });
    fireEvent.change(screen.getAllByRole('textbox')[1]!, { target: { value: 'smog' } });
    expect(g).toHaveBeenCalledWith('smog');
  });
  it('ORDERING: the keys in the new order', () => {
    const f = run('ORDERING', { items: [{ key: 'x', text: 'first' }, { key: 'y', text: 'second' }] });
    fireEvent.click(screen.getByRole('button', { name: /Đưa mục 1 xuống/ }));
    expect(f).toHaveBeenCalledWith(['y', 'x']);
  });
  it('ESSAY: a textarea without spell-check and with a word count; SPEAKING shows the topic', () => {
    const f = run('ESSAY', { prompt: 'Write.', minWords: 5 }, 'one two three');
    const box = screen.getByRole('textbox');
    expect(box).toHaveAttribute('spellcheck', 'false');
    expect(box).toHaveAttribute('autocorrect', 'off');
    expect(box).toHaveAttribute('autocomplete', 'off');
    expect(screen.getByTestId('word-count')).toHaveTextContent('3 từ');
    fireEvent.change(box, { target: { value: 'a b' } });
    expect(f).toHaveBeenCalledWith('a b');
    run('SPEAKING_RESPONSE', { topic: 'Your hometown', questions: ['Where?'] });
    expect(screen.getByText('Your hometown')).toBeInTheDocument();
  });
  it('knows the interaction kind of every type code and guesses an unknown one from its content', () => {
    expect(kindOfQuestion('TOEIC_P6_TEXT_COMPLETION', {})).toBe('GAP_SELECT');
    expect(kindOfQuestion('IELTS_R_IDENTIFYING_WRITERS_VIEWS', {})).toBe('YES_NO_NG');
    expect(kindOfQuestion('NEW_TYPE', { items: [], choices: [] })).toBe('MATCHING');
  });
});
