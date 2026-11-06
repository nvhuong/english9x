import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

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
);
