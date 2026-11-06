import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from './Table';

describe('Table', () => {
  it('is a table named by its caption, with column headers and rows', () => {
    render(
      <Table caption="Câu hỏi chờ duyệt">
        <TableHead><TableRow><TableHeaderCell>Mã</TableHeaderCell><TableHeaderCell>Dạng</TableHeaderCell></TableRow></TableHead>
        <TableBody><TableRow><TableCell>Q-1</TableCell><TableCell>MCQ</TableCell></TableRow></TableBody>
      </Table>,
    );
    expect(screen.getByRole('table', { name: 'Câu hỏi chờ duyệt' })).toBeInTheDocument();
    expect(screen.getAllByRole('columnheader')).toHaveLength(2);
    expect(screen.getByRole('cell', { name: 'Q-1' })).toBeInTheDocument();
  });

  it('the scroll region is keyboard focusable', async () => {
    render(<Table caption="T" captionHidden><TableBody><TableRow><TableCell>x</TableCell></TableRow></TableBody></Table>);
    await userEvent.setup().tab();
    expect(screen.getByRole('region', { name: 'T' })).toHaveFocus();
  });

  it('sortable header: aria-sort reflects state and the button triggers sorting by keyboard', async () => {
    const onSort = vi.fn();
    const { rerender } = render(
      <Table caption="T"><TableHead><TableRow><TableHeaderCell onSort={onSort}>Mã</TableHeaderCell></TableRow></TableHead></Table>,
    );
    expect(screen.getByRole('columnheader')).toHaveAttribute('aria-sort', 'none');
    const user = userEvent.setup();
    await user.tab();
    await user.tab();
    await user.keyboard('{Enter}');
    expect(onSort).toHaveBeenCalledTimes(1);
    rerender(<Table caption="T"><TableHead><TableRow><TableHeaderCell onSort={onSort} sort="descending">Mã</TableHeaderCell></TableRow></TableHead></Table>);
    expect(screen.getByRole('columnheader')).toHaveAttribute('aria-sort', 'descending');
  });
});
