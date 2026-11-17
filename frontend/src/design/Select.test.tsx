import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Select } from './Select';

const options = [
  { value: 'ac', label: 'IELTS Academic' },
  { value: 'gt', label: 'IELTS General' },
  { value: 'toeic', label: 'TOEIC L&R' },
];

describe('Select', () => {
  it('is a labelled combobox, opens with the keyboard and selects with Enter', async () => {
    const onValueChange = vi.fn();
    const user = userEvent.setup();
    render(<Select label="Kỳ thi" options={options} onValueChange={onValueChange} />);
    const trigger = screen.getByRole('combobox', { name: 'Kỳ thi' });
    expect(trigger).toHaveTextContent('Chọn…');
    trigger.focus();
    await user.keyboard('{ArrowDown}');
    expect(await screen.findByRole('listbox')).toBeInTheDocument();
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onValueChange).toHaveBeenCalledWith('gt');
    expect(trigger).toHaveTextContent('IELTS General');
  });

  it('shows the error as an alert and marks the trigger invalid', () => {
    render(<Select label="Kỳ thi" options={options} error="Hãy chọn một kỳ thi" />);
    expect(screen.getByRole('combobox')).toBeInvalid();
    expect(screen.getByRole('alert')).toHaveTextContent('Hãy chọn một kỳ thi');
  });

  it('bounds the list to the room the screen has, so a long list scrolls instead of running out of the window', async () => {
    const user = userEvent.setup();
    render(<Select label="Kỳ thi" options={options} />);
    screen.getByRole('combobox', { name: 'Kỳ thi' }).focus();
    await user.keyboard('{ArrowDown}');
    const viewport = (await screen.findByRole('listbox')).querySelector('[data-radix-select-viewport]');
    expect(viewport?.getAttribute('class')).toContain('--radix-select-content-available-height');
  });
});
