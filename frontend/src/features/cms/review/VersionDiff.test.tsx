import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { diffVersions } from './diff';
import { VersionDiff } from './VersionDiff';

const before = { content: { stem: 'Old stem', options: [{ key: 'A', text: 'one' }, { key: 'B', text: 'two' }] }, points: 1 };
const after = { content: { stem: 'New stem', options: [{ key: 'A', text: 'one' }, { key: 'B', text: 'TWO' }, { key: 'C', text: 'three' }] }, points: 1 };

describe('VersionDiff', () => {
  it('highlightsChangedFields: only the fields that differ are marked, by path', () => {
    render(<VersionDiff before={before} after={after} />);
    const rows = screen.getAllByTestId('diff-row');
    const changed = rows.filter((r) => r.getAttribute('data-changed') === 'true').map((r) => r.querySelector('[data-testid="diff-path"]')!.textContent);
    expect(changed).toEqual(['content.stem', 'content.options[1].text', 'content.options[2].key', 'content.options[2].text']);
    const same = rows.filter((r) => r.getAttribute('data-changed') === 'false').map((r) => r.querySelector('[data-testid="diff-path"]')!.textContent);
    expect(same).toEqual(['content.options[0].key', 'content.options[0].text', 'content.options[1].key', 'points']);
    expect(screen.getByText('Old stem')).toBeInTheDocument();
    expect(screen.getByText('New stem')).toBeInTheDocument();
  });

  it('can hide the unchanged fields', async () => {
    render(<VersionDiff before={before} after={after} />);
    await userEvent.click(screen.getByRole('checkbox', { name: 'Chỉ hiện trường thay đổi' }));
    expect(screen.getAllByTestId('diff-row')).toHaveLength(4);
  });

  it('marks every field new when there is no earlier version, and a removed field too', () => {
    expect(diffVersions(null, { a: 1, b: 'x' }).map((d) => d.status)).toEqual(['added', 'added']);
    expect(diffVersions({ a: 1, gone: 2 }, { a: 1 }).map((d) => [d.path, d.status])).toEqual([['a', 'same'], ['gone', 'removed']]);
    expect(diffVersions({ list: [] }, { list: ['x'] }).map((d) => d.status).sort()).toEqual(['added', 'removed']);
  });
});
