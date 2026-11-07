import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { useContextStore, switchContext } from '../../app/stores/context';
import { useRoleStore } from '../../app/stores/roles';
import { json, me, stubApi } from '../auth/testUtils';
import { OrgContextBootstrap } from './OrgContextBootstrap';
import { useOrgStore, writeStoredContext } from './orgContext';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

const A = 'aaaaaaaa-0000-0000-0000-00000000000a';
const GONE = 'cccccccc-0000-0000-0000-00000000000c';
const USER = me().id;
const body = (over = {}) => ({
  contextOrgId: null,
  organizations: [{ id: A, name: 'Trung tâm A', type: 'CENTER' }],
  grants: [{ role: 'TEACHER', orgId: A, examCode: 'IELTS' }],
  ...over,
});

function mount(client = new QueryClient()) {
  render(<QueryClientProvider client={client}><OrgContextBootstrap /></QueryClientProvider>);
  return client;
}

beforeEach(() => {
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: null });
  useOrgStore.setState({ orgs: [], loaded: false });
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

describe('OrgContextBootstrap', () => {
  it('loads organisations and grants when a person is signed in, and never sends X-Org-Context for that call', async () => {
    useContextStore.setState({ orgId: GONE }); // a stale value must not turn the call into a 403
    const calls = stubApi({ 'GET /api/me/context': () => json(200, body()) });
    mount();
    await waitFor(() => expect(useOrgStore.getState().loaded).toBe(true));
    expect(useRoleStore.getState().grants).toEqual([{ role: 'TEACHER', org: A, exam: 'IELTS' }]);
    expect(calls[0]!.headers.get('X-Org-Context')).toBeNull();
    expect(calls[0]!.headers.get('Authorization')).toBe('Bearer tok');
  });

  it('a stored organisation the person no longer belongs to falls back to personal and settles (one refresh for the change, no loop)', async () => {
    writeStoredContext(USER, GONE);
    useContextStore.setState({ orgId: GONE });
    const calls = stubApi({ 'GET /api/me/context': () => json(200, body()) });
    mount();
    await waitFor(() => expect(useOrgStore.getState().loaded).toBe(true));
    expect(useContextStore.getState().orgId).toBeNull();
    await new Promise((r) => setTimeout(r, 60));
    const settled = calls.length;
    expect(settled).toBeLessThanOrEqual(2); // the first answer, and one refresh because the context changed
    await new Promise((r) => setTimeout(r, 150));
    expect(calls).toHaveLength(settled);
    expect(calls.every((c) => c.headers.get('X-Org-Context') === null)).toBe(true);
  });

  it('restores a stored organisation the person still belongs to', async () => {
    writeStoredContext(USER, A);
    stubApi({ 'GET /api/me/context': () => json(200, body()) });
    mount();
    await waitFor(() => expect(useContextStore.getState().orgId).toBe(A));
  });

  it('reloads the roles when the context changes (and after a switch the call is still without the header)', async () => {
    let n = 0;
    const calls = stubApi({
      'GET /api/me/context': () => json(200, body({ grants: n++ === 0 ? [] : [{ role: 'ORG_ADMIN', orgId: A, examCode: null }] })),
    });
    const client = mount();
    await waitFor(() => expect(useOrgStore.getState().loaded).toBe(true));
    expect(useRoleStore.getState().grants).toEqual([]);
    await switchContext(client, A);
    await waitFor(() => expect(useRoleStore.getState().grants).toEqual([{ role: 'ORG_ADMIN', org: A, exam: null }]));
    expect(calls.every((c) => c.headers.get('X-Org-Context') === null)).toBe(true);
  });

  it('does nothing for a visitor, and forgets everything when the person signs out', async () => {
    useAuthStore.setState({ accessToken: null, status: 'anonymous', user: null });
    const calls = stubApi({});
    mount();
    await new Promise((r) => setTimeout(r, 30));
    expect(calls).toHaveLength(0);
    expect(useOrgStore.getState().loaded).toBe(false);
  });
});
