import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
  {
    // Tests wait on a condition (waitFor, findBy…, a flag the test sets), never on a fixed delay (owner, Q55): a fixed
    // wait is either too short under load (a flaky test) or longer than needed. A timer a test double needs for its own
    // behaviour (a stub worker answering later) is not a wait and is not flagged.
    files: ['**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': ['error',
        { selector: "NewExpression[callee.name='Promise'] CallExpression:matches([callee.name='setTimeout'], [callee.property.name='setTimeout'])",
          message: 'A fixed wait in a test: wait on a condition instead (waitFor, findBy…, or a flag the test sets).' },
        { selector: "CallExpression[callee.property.name='waitForTimeout']",
          message: 'A fixed wait in a test: wait on a condition instead (waitFor, findBy…, or a flag the test sets).' },
        { selector: "ImportDeclaration[source.value=/^(node:)?timers\\u002Fpromises$/]",
          message: 'A sleep in a test (timers/promises): wait on a condition instead.' },
        { selector: "CallExpression:matches([callee.name='promisify'], [callee.property.name='promisify']) > Identifier[name='setTimeout']",
          message: 'A sleep in a test (a promisified setTimeout): wait on a condition instead.' },
      ],
    },
  },
])
