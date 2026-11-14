import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import { sha256Hex } from './sha256'

// #384: the content hash is a real SHA-256 (256 bits), checked against Node's own implementation, including lengths
// around the 64-byte block and its padding boundary (55 / 56 / 63 / 64 bytes) and non-ASCII text (UTF-8).
describe('sha256Hex', () => {
  const node = (t: string) => createHash('sha256').update(t, 'utf8').digest('hex')
  it('matches node:crypto for the empty string, block boundaries, long and non-ASCII inputs', () => {
    const inputs = ['', 'abc', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(63), 'a'.repeat(64), 'a'.repeat(65),
      'x'.repeat(1000), '°C — km/h ✓ 日本', JSON.stringify({ k: [1, 2, null], s: 'é' }).repeat(300)]
    for (const t of inputs) expect(sha256Hex(t)).toBe(node(t))
  })
})
