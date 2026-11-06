import type { Decorator, Preview } from '@storybook/react-vite';
import { useEffect, type ReactNode } from 'react';
import '../src/design/tokens.css';
import i18n, { LANGUAGES } from '../src/i18n';

function Globals({ theme, lang, children }: { theme: string; lang: string; children: ReactNode }) {
  document.documentElement.setAttribute('data-theme', theme);
  useEffect(() => {
    void i18n.changeLanguage(lang);
    document.documentElement.lang = lang;
  }, [lang]);
  return <div className="min-h-24 bg-paper p-4 text-ink">{children}</div>;
}

const withGlobals: Decorator = (Story, ctx) => (
  <Globals theme={(ctx.globals.theme as string) || 'light'} lang={(ctx.globals.lang as string) || 'vi'}>
    <Story />
  </Globals>
);

const preview: Preview = {
  decorators: [withGlobals],
  globalTypes: {
    theme: { description: 'Theme', toolbar: { icon: 'circlehollow', items: ['light', 'dark'], dynamicTitle: true } },
    lang: { description: 'Language', toolbar: { icon: 'globe', items: [...LANGUAGES], dynamicTitle: true } },
  },
  initialGlobals: { theme: 'light', lang: 'vi' },
  parameters: {
    layout: 'fullscreen',
    a11y: { test: 'error' },
  },
};

export default preview;
