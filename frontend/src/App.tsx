import { useState } from 'react';
import { RouterProvider } from 'react-router-dom';
import { createAppRouter } from './app/router';
import { AppProviders } from './app/providers';
import './i18n';

/** Application root: providers + the router built from `src/routes/<area>/*.routes.ts`. */
export default function App() {
  const [router] = useState(() => createAppRouter());
  return (
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  );
}
