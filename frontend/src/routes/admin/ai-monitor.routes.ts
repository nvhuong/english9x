import { defineRoutes } from '../../app/routeTypes';

/** SYS_ADMIN: failed jobs, AI cost and AI-vs-teacher deviation (F56). The area guard asks for SYS_ADMIN; the server checks again. */
export default defineRoutes([
  {
    path: 'ai-monitor',
    role: 'SYS_ADMIN',
    nav: { labelKey: 'nav.aiMonitor', ns: 'aimonitor', order: 91 },
    lazy: async () => ({ Component: (await import('../../features/admin/AiMonitorPage')).default }),
  },
]);
