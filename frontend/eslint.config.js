// ESLint flat config — the G1 TypeScript static-analysis gate (CodingStandards.md §1, §4).
// Enforces typescript-eslint recommended rules and react-hooks; runs with --max-warnings 0 in CI.
// Prettier owns formatting: eslint-config-prettier disables any stylistic rules that would conflict
// (DEBT-004 — Prettier is wired via the `format:check` script + CI step, not ESLint).
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import prettier from 'eslint-config-prettier';
import globals from 'globals';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  // Test files also run under Node/Vitest globals.
  {
    files: ['**/*.test.{ts,tsx}', 'src/test/**/*.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
  },
  // Must be last: turn off ESLint rules that conflict with Prettier.
  prettier,
);
