import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { readStoredContext, useOrgStore } from '../features/org/orgContext';
import { ContextSwitcher } from './ContextSwitcher';
import { useAuthStore } from './stores/auth';
import { useContextStore } from './stores/context';

const A = 'aaaaaaaa-0000-0000-0000-00000000000a';
const B = 'bbbbbbbb-0000-0000-0000-00000000000b';
const USER = '11111111-1111-1111-1111-111111111111';

function ui() {
  const client = new QueryClient();
  render(
    <QueryClientProvider client={client}>
      <ContextSwitcher />
    </QueryClientProvider>,
  );
  return client;
}

beforeEach(() => {
  useContextStore.setState({ orgId: null });
  useAuthStore.setState({ status: 'authenticated', user: { id: USER, email: 'a@x.test', displayName: 'An', emailVerified: true, locale: 'vi' } });
  useOrgStore.setState({
    loaded: true,
    orgs: [
      { id: A, name: 'Trung tâm A', type: 'CENTER' },
      { id: B, name: 'Doanh nghiệp B', type: 'ENTERPRISE' },
    ],
  });
});

describe('ContextSwitcher', () => {
  it('lists "Cá nhân" first and then every organisation of the person', async () => {
    ui();
    await userEvent.click(screen.getByRole('combobox', { name: 'Ngữ cảnh' }));
    const options = screen.getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['Cá nhân', 'Trung tâm A', 'Doanh nghiệp B']);
  });

  it('switching changes the context through switchContext and remembers it for this person', async () => {
    ui();
    await userEvent.click(screen.getByRole('combobox', { name: 'Ngữ cảnh' }));
    await userEvent.click(screen.getByRole('option', { name: 'Trung tâm A' }));
    await waitFor(() => expect(useContextStore.getState().orgId).toBe(A));
    expect(readStoredContext(USER)).toBe(A);
    await userEvent.click(screen.getByRole('combobox', { name: 'Ngữ cảnh' }));
    await userEvent.click(screen.getByRole('option', { name: 'Cá nhân' }));
    await waitFor(() => expect(useContextStore.getState().orgId).toBeNull());
    expect(readStoredContext(USER)).toBeNull();
  });

  it('shows the current organisation by name', () => {
    useContextStore.setState({ orgId: B });
    ui();
    expect(screen.getByRole('combobox', { name: 'Ngữ cảnh' })).toHaveTextContent('Doanh nghiệp B');
  });

  it('is absent for a visitor and for a person with no organisation (the personal context is implied)', () => {
    useAuthStore.setState({ status: 'anonymous', user: null });
    const { container } = render(<QueryClientProvider client={new QueryClient()}><ContextSwitcher /></QueryClientProvider>);
    expect(container).toBeEmptyDOMElement();
  });

  it('does not offer an organisation the person does not belong to, even if the store still names it', () => {
    useContextStore.setState({ orgId: 'cccccccc-0000-0000-0000-00000000000c' });
    ui();
    expect(screen.getByRole('combobox', { name: 'Ngữ cảnh' })).toHaveTextContent('Cá nhân');
  });
});
