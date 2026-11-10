import { defineRoutes } from '../../app/routeTypes';

/** Authors and reviewers: the strategy lessons of the exams they write or review (F14). The area guard asks for a CMS role; the server checks again. */
export default defineRoutes([
  {
    path: 'strategy-lessons',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav.lessons', ns: 'lessons', order: 25 },
    lazy: async () => ({ Component: (await import('../../features/cms/lesson-editor/LessonListPage')).default }),
  },
  {
    path: 'strategy-lessons/new',
    role: 'AUTHOR',
    lazy: async () => ({ Component: (await import('../../features/cms/lesson-editor/LessonEditorPage')).default }),
  },
  {
    path: 'strategy-lessons/:id',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/lesson-editor/LessonEditorPage')).default }),
  },
]);
