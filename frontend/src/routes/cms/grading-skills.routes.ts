import { defineRoutes } from '../../app/routeTypes';

/** Authors, reviewers and teachers: the grading skills (rubric, criteria, prompt, samples, result schema) of the Writing and Speaking types (F16). The server checks again. */
export default defineRoutes([
  {
    path: 'grading-skills',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav.gradingSkills', ns: 'skills', order: 28 },
    lazy: async () => ({ Component: (await import('../../features/cms/grading-skills/SkillListPage')).default }),
  },
  {
    path: 'grading-skills/new',
    role: 'AUTHOR',
    lazy: async () => ({ Component: (await import('../../features/cms/grading-skills/SkillEditorPage')).default }),
  },
  {
    path: 'grading-skills/:id',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/grading-skills/SkillEditorPage')).default }),
  },
]);
