import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Button } from './Button';

describe('Button', () => {
  it('is a real button (type=button by default) activated by click, Enter and Space', async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(<Button onClick={onClick}>Lưu</Button>);
    const b = screen.getByRole('button', { name: 'Lưu' });
    expect(b).toHaveAttribute('type', 'button');
    await user.click(b);
    b.focus();
    await user.keyboard('{Enter}');
    await user.keyboard(' ');
    expect(onClick).toHaveBeenCalledTimes(3);
  });

  it('loading: aria-busy, disabled and not clickable', async () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>Gửi</Button>);
    const b = screen.getByRole('button', { name: 'Gửi' });
    expect(b).toHaveAttribute('aria-busy', 'true');
    expect(b).toBeDisabled();
    await userEvent.setup().click(b);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('asChild styles the child element (link) without nesting a button', () => {
    render(<Button asChild><a href="/x">Đi</a></Button>);
    expect(screen.getByRole('link', { name: 'Đi' })).toHaveAttribute('href', '/x');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
