import { defineRoutes } from '../../app/routeTypes';

/** Reviewers (and authors, for their own pending submissions): the review queue, one review with the version comparison, a whole bundle (F17, F18). The server scopes every answer. */
export default defineRoutes([
  {
    path: 'reviews',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav', ns: 'review', order: 12 },
    lazy: async () => ({ Component: (await import('../../features/cms/review/ReviewQueuePage')).default }),
  },
  {
    path: 'reviews/bundles/:bundleId',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/review/BundleReviewPage')).default }),
  },
  {
    path: 'reviews/:id',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/review/ReviewPage')).default }),
  },
]);
