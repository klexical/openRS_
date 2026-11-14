/**
 * A value as exact text for comparisons in tests (the rule of ticket 02's backup test): a number is its 8 IEEE-754
 * bytes (NaN, −0 and ±Infinity told apart), a key holding undefined differs from a missing key, key order counts, and
 * arrays differ from objects. JSON.stringify would hide all of these.
 */
export function exact(v: unknown): string {
  if (typeof v === 'number') return `n${Buffer.from(new Float64Array([v]).buffer).toString('hex')}`
  if (v === undefined) return 'u'
  if (v === null || typeof v !== 'object') return `${typeof v}:${JSON.stringify(v)}`
  if (Array.isArray(v)) return `[${Array.from({ length: v.length }, (_, i) => (i in v ? exact(v[i]) : 'hole')).join(',')}]`
  if (Object.prototype.toString.call(v) !== '[object Object]') throw new Error(`unexpected ${Object.prototype.toString.call(v)}`)
  return `{${Object.keys(v).map((k) => `${JSON.stringify(k)}:${exact((v as Record<string, unknown>)[k])}`).join(',')}}`
}
