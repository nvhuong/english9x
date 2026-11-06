import type { Meta, StoryObj } from '@storybook/react-vite';
import { useEffect, type ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EmptyState, Badge, Button } from '../../design';
import { NavContext, type NavByArea } from '../nav';
import { useRoleStore } from '../stores/roles';
import { AdminLayout, CmsLayout, ExamLayout, LearnerLayout, OrgLayout } from './layouts';

const nav: NavByArea = {
  learner: [{ to: '/courses', labelKey: 'area.learner' }, { to: '/preferences', labelKey: 'preferences.title' }],
  cms: [{ to: '/cms/queue', labelKey: 'area.cms' }, { to: '/cms/bank', labelKey: 'area.exam' }],
  org: [{ to: '/org/members', labelKey: 'area.org' }],
  admin: [{ to: '/admin/orgs', labelKey: 'area.admin' }],
  exam: [],
};

function Frame({ at, element, children }: { at: string; element: ReactNode; children: ReactNode }) {
  useEffect(() => {
    useRoleStore.setState({ grants: [{ role: 'SYS_ADMIN', org: null, exam: null }] });
    return () => useRoleStore.setState({ grants: null });
  }, []);
  return (
    <NavContext.Provider value={nav}>
      <MemoryRouter initialEntries={[at]}>
        <Routes>
          <Route path={at} element={element}>
            <Route index element={children} />
          </Route>
        </Routes>
      </MemoryRouter>
    </NavContext.Provider>
  );
}

const meta = { title: 'App/Layouts', parameters: { layout: 'fullscreen' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

export const Learner: S = {
  render: () => (
    <Frame at="/" element={<LearnerLayout />}>
      <EmptyState headingLevel={1} icon="📚" title="Chưa có khoá học nào" description="Danh mục khoá học sẽ xuất hiện ở đây." />
    </Frame>
  ),
};
export const Cms: S = {
  render: () => (
    <Frame at="/cms" element={<CmsLayout />}>
      <h1 className="text-xl font-bold">Hàng việc của tôi <Badge tone="warning">3 chờ duyệt</Badge></h1>
    </Frame>
  ),
};
export const Org: S = { render: () => <Frame at="/org" element={<OrgLayout />}><h1 className="text-xl font-bold">Tổng quan suất học</h1></Frame> };
export const Admin: S = { render: () => <Frame at="/admin" element={<AdminLayout />}><h1 className="text-xl font-bold">Đơn chờ xác nhận</h1></Frame> };
export const Exam: S = {
  render: () => (
    <Frame at="/exam" element={<ExamLayout />}>
      <div className="p-6"><h1 className="mb-3 text-xl font-bold">IELTS Reading — Passage 1</h1><Button>Câu tiếp theo</Button></div>
    </Frame>
  ),
};
