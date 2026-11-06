import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EmptyState } from './EmptyState';

describe('EmptyState', () => {
  it('renders a heading at the requested level, description and action; icon is hidden from AT', () => {
    render(<EmptyState title="Chưa có khoá học" description="Hãy quay lại sau." icon="📚" headingLevel={1} action={<a href="/x">Xem danh mục</a>} />);
    expect(screen.getByRole('heading', { level: 1, name: 'Chưa có khoá học' })).toBeInTheDocument();
    expect(screen.getByText('Hãy quay lại sau.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem danh mục' })).toBeInTheDocument();
    expect(screen.getByText('📚')).toHaveAttribute('aria-hidden', 'true');
  });
  it('can announce itself as an alert', () => {
    render(<EmptyState title="Không có quyền" alert />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
