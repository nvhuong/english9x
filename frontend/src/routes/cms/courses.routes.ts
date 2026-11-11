import { defineRoutes } from '../../app/routeTypes';

/** Authors and reviewers: the courses built from published lessons and tests (F19). The area guard asks for a CMS role; the server checks again. */
export default defineRoutes([
  {
    path: 'courses',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav.courses', ns: 'courses', order: 30 },
    lazy: async () => ({ Component: (await import('../../features/cms/course-builder/CourseListPage')).default }),
  },
  {
    path: 'courses/new',
    role: 'AUTHOR',
    lazy: async () => ({ Component: (await import('../../features/cms/course-builder/CourseBuilderPage')).default }),
  },
  {
    path: 'courses/:id',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/course-builder/CourseBuilderPage')).default }),
  },
]);
