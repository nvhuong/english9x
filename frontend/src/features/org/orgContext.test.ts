import { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { useContextStore } from '../../app/stores/context';
import { useRoleStore } from '../../app/stores/roles';
import { applyMeContext, grantsFromApi, readStoredContext, resolveContext, useOrgStore, writeStoredContext, type MeContextBody } from './orgContext';

const A = 'aaaaaaaa-0000-0000-0000-00000000000a';
const B = 'bbbbbbbb-0000-0000-0000-00000000000b';
const USER = '11111111-1111-1111-1111-111111111111';
const orgs = [
  { id: A, name: 'Trung tâm A', type: 'CENTER' as const },
  { id: B, name: 'Doanh nghiệp B', type: 'ENTERPRISE' as const },
];

beforeEach(() => {
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: null });
  useOrgStore.setState({ orgs: [], loaded: false });
});

describe('grantsFromApi', () => {
  it('maps the server grants to the role store shape', () => {
    expect(grantsFromApi([{ role: 'AUTHOR', orgId: null, examCode: 'IELTS' }, { role: 'ORG_ADMIN', orgId: A, examCode: null }])).toEqual([
      { role: 'AUTHOR', org: null, exam: 'IELTS' },
      { role: 'ORG_ADMIN', org: A, exam: null },
    ]);
  });
});

describe('resolveContext', () => {
  it('keeps a stored organisation the person still belongs to', () => {
    expect(resolveContext(A, null, orgs)).toBe(A);
  });
  it('falls back to the personal context for an organisation the person left (no error loop)', () => {
    expect(resolveContext('cccccccc-0000-0000-0000-00000000000c', null, orgs)).toBeNull();
    expect(resolveContext('cccccccc-0000-0000-0000-00000000000c', 'cccccccc-0000-0000-0000-00000000000c', orgs)).toBeNull();
  });
  it('a current choice made in this session wins over the stored one while it is still valid', () => {
    expect(resolveContext(A, B, orgs)).toBe(B);
  });
  it('nothing stored and nothing chosen: personal', () => {
    expect(resolveContext(null, null, orgs)).toBeNull();
  });
});

describe('stored context', () => {
  it('belongs to one person: another user does not inherit it', () => {
    writeStoredContext(USER, A);
    expect(readStoredContext(USER)).toBe(A);
    expect(readStoredContext('22222222-2222-2222-2222-222222222222')).toBeNull();
    writeStoredContext(USER, null);
    expect(readStoredContext(USER)).toBeNull();
  });
  it('survives unreadable storage', () => {
    localStorage.setItem('e9x.context', '{not json');
    expect(readStoredContext(USER)).toBeNull();
    localStorage.setItem('e9x.context', JSON.stringify({ userId: USER, orgId: 42 }));
    expect(readStoredContext(USER)).toBeNull();
  });
});

describe('applyMeContext', () => {
  const body: MeContextBody = {
    contextOrgId: null,
    organizations: orgs,
    grants: [{ role: 'ORG_ADMIN', orgId: A, examCode: null }],
  };

  it('fills the role store and the organisation list', async () => {
    await applyMeContext(new QueryClient(), USER, body);
    expect(useRoleStore.getState().grants).toEqual([{ role: 'ORG_ADMIN', org: A, exam: null }]);
    expect(useOrgStore.getState()).toMatchObject({ orgs, loaded: true });
  });

  it('restores the stored organisation once, through switchContext', async () => {
    writeStoredContext(USER, A);
    await applyMeContext(new QueryClient(), USER, body);
    expect(useContextStore.getState().orgId).toBe(A);
  });

  it('drops a context the person no longer belongs to and forgets it', async () => {
    useContextStore.setState({ orgId: 'cccccccc-0000-0000-0000-00000000000c' });
    writeStoredContext(USER, 'cccccccc-0000-0000-0000-00000000000c');
    await applyMeContext(new QueryClient(), USER, body);
    expect(useContextStore.getState().orgId).toBeNull();
    expect(readStoredContext(USER)).toBeNull();
  });
});
