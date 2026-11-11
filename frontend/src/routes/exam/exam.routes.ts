import { defineRoutes } from '../../app/routeTypes';

/** The exam room (F25-F29, F32): start a test version, sit an attempt. */
export default defineRoutes([
  {
    path: 'start/:testVersionId',
    lazy: async () => ({ Component: (await import('../../features/exam/ExamPages')).StartExamPage }),
  },
  {
    path: 'attempts/:attemptId',
    lazy: async () => ({ Component: (await import('../../features/exam/ExamPages')).ExamAttemptPage }),
  },
]);
