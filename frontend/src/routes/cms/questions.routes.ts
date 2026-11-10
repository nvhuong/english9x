import { defineRoutes } from '../../app/routeTypes';

/** Authors and reviewers: the questions of the exams they write or review (F09, F18). The area guard asks for a CMS role; the server checks again. */
export default defineRoutes([
  {
    path: 'questions',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav.questions', ns: 'questions', order: 20 },
    lazy: async () => ({ Component: (await import('../../features/cms/questions/QuestionListPage')).default }),
  },
  {
    path: 'questions/new',
    role: 'AUTHOR',
    lazy: async () => ({ Component: (await import('../../features/cms/questions/QuestionEditorPage')).default }),
  },
  {
    path: 'questions/:id',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/questions/QuestionEditorPage')).default }),
  },
  {
    // "Xem trước như học viên" (F10): the content as a learner sees it, never the solution side.
    path: 'questions/:id/preview',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/preview/PreviewPage')).default }),
  },
]);
