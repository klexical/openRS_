// Harness build: node_modules/.bin/vite build --config scripts/idb-check/vite.config.mjs (from web/)
import { dirname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
export default {
  root: here,
  base: './',
  logLevel: 'warn',
  publicDir: join(here, '../../src/lib/__fixtures__/android-exports'),
  build: { outDir: process.env.IDB_CHECK_OUT ?? join(tmpdir(), 'sapphire-idb-check'), emptyOutDir: true, minify: false },
}
