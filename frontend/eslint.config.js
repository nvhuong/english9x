import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import e9x from './eslint-rules/index.js';

export default tseslint.config(
  { ignores: ['dist', 'public', 'node_modules', 'storybook-static', 'src/api/generated', '.tmp-api-gen-*'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { files: ['scripts/**', '**/*.test.ts', '.storybook/**'], languageOptions: { globals: { ...globals.node } } },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser } },
    plugins: { 'react-hooks': reactHooks },
    rules: { ...reactHooks.configs.recommended.rules },
  },
  {
    // Context isolation (Review Focus #1): query keys must come from ctxKey/useCtxKey/publicKey.
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/api/generated/**'],
    plugins: { e9x },
    rules: { 'e9x/query-key-helper': 'error' },
  },
);
