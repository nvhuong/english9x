import { defineRoutes } from '../../app/routeTypes';

/** Authors and reviewers: the conversion tables (raw to scaled score) of the exams they write or review (F15). The area guard asks for a CMS role; the server checks again. */
export default defineRoutes([
  {
    path: 'score-tables',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav.scoreTables', ns: 'scoring', order: 27 },
    lazy: async () => ({ Component: (await import('../../features/cms/score-tables/ScoreTableListPage')).default }),
  },
  {
    path: 'score-tables/new',
    role: 'AUTHOR',
    lazy: async () => ({ Component: (await import('../../features/cms/score-tables/ScoreTableEditorPage')).default }),
  },
  {
    path: 'score-tables/:id',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/score-tables/ScoreTableEditorPage')).default }),
  },
]);
