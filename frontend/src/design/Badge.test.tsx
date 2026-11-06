import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Badge } from './Badge';

describe('Badge', () => {
  it('conveys status by its text', () => {
    render(<Badge tone="warning">Chờ duyệt</Badge>);
    expect(screen.getByText('Chờ duyệt')).toBeVisible();
  });
});
