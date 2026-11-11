import { defineRoutes } from '../../app/routeTypes';

/** The learner's side of a course (F22, F23): my courses, the map of one, a lesson, a chapter test. Open to every signed-in person; the server answers 403 without a plan. */
export default defineRoutes([
  {
    path: 'learn',
    nav: { labelKey: 'nav.learn', ns: 'learning', order: 15 },
    lazy: async () => ({ Component: (await import('../../features/learning/MyLearningPage')).default }),
  },
  {
    path: 'learn/courses/:courseId',
    lazy: async () => ({ Component: (await import('../../features/learning/ChapterMapPage')).default }),
  },
  {
    path: 'learn/lessons/:lessonId',
    lazy: async () => ({ Component: (await import('../../features/learning/LessonPage')).default }),
  },
  {
    path: 'learn/chapter-tests/:lessonId',
    lazy: async () => ({ Component: (await import('../../features/learning/ChapterTestPage')).default }),
  },
]);
