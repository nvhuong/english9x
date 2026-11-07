import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useOrgStore } from '../../features/org/orgContext';
import { useAuthStore } from '../stores/auth';
import { useContextStore } from '../stores/context';
import { useRoleStore } from '../stores/roles';
import { RequireRole } from './RequireRole';

const secret = 'ĐÁP ÁN BÍ MẬT';
function ui(props: Partial<React.ComponentProps<typeof RequireRole>> = {}) {
  return render(
    <MemoryRouter>
      <RequireRole role="AUTHOR" {...props}>
        <p>{secret}</p>
      </RequireRole>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useContextStore.setState({ orgId: null });
  useRoleStore.setState({ grants: null });
});

describe('RequireRole', () => {
  it('renders denied (never the content) when roles are not loaded / missing', () => {
    ui();
    expect(screen.queryByText(secret)).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Bạn chưa có quyền xem trang này' })).toBeInTheDocument();
  });

  it('renders denied for a different role, and for an unknown role string', () => {
    useRoleStore.setState({ grants: [{ role: 'LEARNER', org: null, exam: null }, { role: 'WIZARD' as never, org: null, exam: null }] });
    ui();
    expect(screen.queryByText(secret)).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('renders the content when the role is granted', () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: null }] });
    ui();
    expect(screen.getByText(secret)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('honours the exam scope', () => {
    useRoleStore.setState({ grants: [{ role: 'AUTHOR', org: null, exam: 'IELTS_AC' }] });
    const { unmount } = ui({ exam: 'IELTS_AC' });
    expect(screen.getByText(secret)).toBeInTheDocument();
    unmount();
    ui({ exam: 'TOEIC_LR' });
    expect(screen.queryByText(secret)).not.toBeInTheDocument();
  });

  it('uses a custom fallback when given', () => {
    ui({ fallback: <p>riêng</p> });
    expect(screen.getByText('riêng')).toBeInTheDocument();
  });
});

describe('RequireRole while the roles load', () => {
  it('shows a loading note, not "no access", for a signed-in person whose roles have not arrived yet', async () => {
    const { useAuthStore } = await import('../stores/auth');
    useAuthStore.setState({ status: 'authenticated', user: { id: 'u', email: 'a@x.test', displayName: null, emailVerified: true, locale: 'vi' } });
    ui();
    expect(screen.queryByText(secret)).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Bạn chưa có quyền xem trang này' })).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải');
    useAuthStore.setState({ status: 'anonymous', user: null });
  });

  it('still says "no access" to a signed-in person once the roles are known and do not match', async () => {
    const { useAuthStore } = await import('../stores/auth');
    useAuthStore.setState({ status: 'authenticated', user: { id: 'u', email: 'a@x.test', displayName: null, emailVerified: true, locale: 'vi' } });
    useRoleStore.setState({ grants: [{ role: 'LEARNER', org: null, exam: null }] });
    ui();
    expect(screen.getByRole('heading', { name: 'Bạn chưa có quyền xem trang này' })).toBeInTheDocument();
    useAuthStore.setState({ status: 'anonymous', user: null });
  });

  describe('when the roles could not be loaded', () => {
    afterEach(() => {
      useAuthStore.setState({ status: 'anonymous', user: null });
      useOrgStore.setState({ loadFailed: false, retryLoad: null });
    });

    it('says so with a retry instead of loading for ever, and still shows nothing guarded', async () => {
      const retry = vi.fn();
      useAuthStore.setState({ status: 'authenticated' });
      useOrgStore.setState({ loadFailed: true, retryLoad: retry });
      ui();
      expect(screen.queryByText(secret)).not.toBeInTheDocument();
      expect(screen.getByRole('alert')).toHaveTextContent('Không tải được quyền của bạn');
      expect(screen.queryByRole('status')).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
      expect(retry).toHaveBeenCalledOnce();
      expect(screen.queryByText(secret)).not.toBeInTheDocument();
    });
  });
});
