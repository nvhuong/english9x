import { defineRoutes } from '../../app/routeTypes';

/** Public sign-in family of pages (learner area, mounted at "/"). No role: anyone may open them. */
export default defineRoutes([
  { path: 'login', lazy: async () => ({ Component: (await import('../../features/auth/pages/LoginPage')).default }) },
  { path: 'login/google', lazy: async () => ({ Component: (await import('../../features/auth/pages/GoogleCallbackPage')).default }) },
  { path: 'register', lazy: async () => ({ Component: (await import('../../features/auth/pages/RegisterPage')).default }) },
  { path: 'verify-email', lazy: async () => ({ Component: (await import('../../features/auth/pages/VerifyEmailPage')).default }) },
  { path: 'forgot-password', lazy: async () => ({ Component: (await import('../../features/auth/pages/ForgotPasswordPage')).default }) },
  { path: 'reset-password', lazy: async () => ({ Component: (await import('../../features/auth/pages/ResetPasswordPage')).default }) },
]);
