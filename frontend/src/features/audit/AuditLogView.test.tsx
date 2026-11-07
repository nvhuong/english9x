import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { resetI18n } from '../../i18n';
import { AuditLogView } from './AuditLogView';
import { detailLines, dayStartIso, nextDayStartIso } from './auditText';
import type { AuditEntry } from './queries';

const entry = (over: Partial<AuditEntry> = {}): AuditEntry => ({
  id: '00000000-0000-0000-0000-000000000001', seq: 1, at: '2026-11-07T03:00:00Z', actorKind: 'USER', actorId: 'a1', actorName: 'An Nguyen',
  actorEmail: 'an@example.test', orgId: undefined, orgName: undefined, action: 'role.change', targetType: 'user', targetId: 'u-1',
  detail: { orgId: 'o-1', before: ['LEARNER'], after: ['AUTHOR/IELTS', 'LEARNER'] }, ...over,
});

describe('AuditLogView', () => {
  it('words a known action and keeps its code, names the actor, and writes the detail as lines', () => {
    render(<AuditLogView entries={[entry()]} />);
    const row = screen.getByTestId('audit-row');
    expect(within(row).getByText('Đổi quyền')).toBeInTheDocument();
    expect(within(row).getByText('role.change')).toBeInTheDocument();
    expect(within(row).getByText('An Nguyen')).toBeInTheDocument();
    expect(within(row).getByText('an@example.test')).toBeInTheDocument();
    expect(within(row).getByText('English9x')).toBeInTheDocument();
    expect(within(row).queryByText('Cá nhân')).toBeNull();
    expect(within(row).getByText('AUTHOR/IELTS, LEARNER')).toBeInTheDocument();
  });

  it('a person accepting an invitation outside any organisation acted for themselves, not for English9x', async () => {
    render(<AuditLogView entries={[entry({ action: 'invitation.accept', targetType: 'invitation' })]} />);
    const row = screen.getByTestId('audit-row');
    expect(within(row).getByText('Cá nhân')).toBeInTheDocument();
    expect(within(row).queryByText('English9x')).toBeNull();
  });

  it('is worded in English too, and shows an unknown action by its code', async () => {
    await resetI18n('en');
    render(<AuditLogView entries={[entry({ action: 'order.confirm' }), entry({ id: '2', action: 'invitation.accept', orgId: 'o-1', orgName: 'Center A' })]} />);
    expect(screen.getByText('Invitation accepted')).toBeInTheDocument();
    expect(screen.getAllByText('order.confirm').length).toBeGreaterThan(0);
    expect(screen.getByText('Center A')).toBeInTheDocument();
  });

  it('shows what the server sent as text, never as markup', () => {
    const attack = '<img src=x onerror=alert(1)>';
    render(<AuditLogView entries={[entry({ detail: { name: attack, nested: { k: '<b>x</b>' } }, targetId: '<script>alert(2)</script>', actorName: '<i>x</i>', actorEmail: undefined })]} />);
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('script')).toBeNull();
    expect(document.querySelector('td b, td i')).toBeNull();
    expect(screen.getByText(attack)).toBeInTheDocument();
    expect(screen.getByText('<script>alert(2)</script>')).toBeInTheDocument();
    expect(screen.getByText('<i>x</i>')).toBeInTheDocument();
  });

  it('marks what the operator did, and an account that no longer exists', () => {
    render(<AuditLogView entries={[entry({ actorKind: 'OPERATOR', actorId: undefined, actorName: undefined, actorEmail: undefined }), entry({ id: '3', actorName: undefined, actorEmail: undefined })]} />);
    expect(screen.getByText('Quản trị viên máy chủ')).toBeInTheDocument();
    expect(screen.getByText('Tài khoản đã bị xoá')).toBeInTheDocument();
  });
});

describe('AuditLogView invitations and context', () => {
  const invitation = (over: Partial<AuditEntry> = {}) => entry({ action: 'invitation.send', targetType: 'invitation', targetId: 'inv-1', detail: { roles: ['LEARNER'] }, ...over });

  it('shows the invited address from the invitation while it exists, and a neutral placeholder once it is gone', () => {
    render(<AuditLogView entries={[invitation({ invitedEmail: 'guest@example.test' }), invitation({ id: '2', invitedEmail: undefined })]} />);
    expect(screen.getByText('guest@example.test')).toBeInTheDocument();
    expect(screen.getByText('Địa chỉ đã được xoá')).toBeInTheDocument();
    expect(screen.getAllByTestId('audit-row')).toHaveLength(2);
  });

  it('does not invent an address for other targets', () => {
    render(<AuditLogView entries={[entry({ invitedEmail: undefined })]} />);
    expect(screen.queryByText('Địa chỉ đã được xoá')).toBeNull();
  });

  it('names an empty context English9x for a person and the system for what the operator did, never "personal"', async () => {
    render(<AuditLogView entries={[entry({ actorKind: 'OPERATOR', actorId: undefined, actorName: undefined, actorEmail: undefined, action: 'sys-admin.grant' })]} />);
    const row = screen.getByTestId('audit-row');
    expect(within(row).getByText('Hệ thống')).toBeInTheDocument();
    expect(within(row).queryByText('Cá nhân')).toBeNull();
    await resetI18n('en');
    render(<AuditLogView entries={[entry({ id: '5' })]} />);
    expect(screen.getAllByText('English9x').length).toBeGreaterThan(0);
    expect(screen.queryByText('Personal')).toBeNull();
  });
});

describe('audit text helpers', () => {
  it('turns values into text lines', () => {
    expect(detailLines({ a: 1, b: null, c: ['x', 'y'], d: { e: 2 }, f: true })).toEqual([['a', '1'], ['b', '–'], ['c', 'x, y'], ['d', '{"e":2}'], ['f', 'true']]);
    expect(detailLines(undefined)).toEqual([]);
  });

  it('turns a chosen day into local day bounds, "to" being the start of the day after', () => {
    expect(dayStartIso('2026-11-07')).toBe(new Date(2026, 10, 7).toISOString());
    expect(nextDayStartIso('2026-11-07')).toBe(new Date(2026, 10, 8).toISOString());
    expect(nextDayStartIso('2026-12-31')).toBe(new Date(2027, 0, 1).toISOString());
    expect(dayStartIso('')).toBeUndefined();
    expect(dayStartIso('7/11/2026')).toBeUndefined();
  });
});
