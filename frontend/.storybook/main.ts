import type { StorybookConfig } from '@storybook/react-vite';

const config: StorybookConfig = {
  stories: ['../src/**/*.stories.@(ts|tsx)'],
  addons: ['@storybook/addon-a11y'],
  framework: '@storybook/react-vite',
  staticDirs: ['../public'], // /fonts/*.woff2 is self-hosted
  core: { disableTelemetry: true },
};

export default config;
