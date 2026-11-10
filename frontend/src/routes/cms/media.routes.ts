import { defineRoutes } from '../../app/routeTypes';

/** Authors: upload audio, images and video for an exam (F12). The area guard asks for a CMS role, this one for AUTHOR; the server checks again. */
export default defineRoutes([
  {
    path: 'media',
    role: 'AUTHOR',
    nav: { labelKey: 'nav.media', ns: 'media', order: 60 },
    lazy: async () => ({ Component: (await import('../../features/cms/media/MediaLibraryPage')).default }),
  },
]);
