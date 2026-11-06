import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Input } from './Input';

describe('Input', () => {
  it('associates label, hint and typing', async () => {
    render(<Input label="Email" hint="Dùng email trường" />);
    const input = screen.getByLabelText('Email');
    expect(input).toHaveAccessibleDescription('Dùng email trường');
    await userEvent.setup().type(input, 'a@b.vn');
    expect(input).toHaveValue('a@b.vn');
  });

  it('marks required in the label text and on the control', () => {
    render(<Input label="Họ tên" required />);
    expect(screen.getByLabelText(/Họ tên/)).toBeRequired();
    expect(screen.getByText('(bắt buộc)')).toBeInTheDocument();
  });

  it('error: invalid, described by and announced as an alert', () => {
    render(<Input label="Email" error="Email không hợp lệ" />);
    const input = screen.getByLabelText('Email');
    expect(input).toBeInvalid();
    expect(input).toHaveAccessibleDescription('Email không hợp lệ');
    expect(screen.getByRole('alert')).toHaveTextContent('Email không hợp lệ');
  });
});
