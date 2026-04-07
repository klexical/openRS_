import { describe, it, expect } from 'vitest'
import { fmtNumber, fmtDuration, fmtTimestamp, fmtDateShort, fmtTemp, fmtSpeed, fmtPsi } from './format'

describe('fmtNumber', () => {
  it('formats with default 1 decimal', () => {
    expect(fmtNumber(3.14159)).toBe('3.1')
  })

  it('formats with custom decimals', () => {
    expect(fmtNumber(3.14159, 3)).toBe('3.142')
  })

  it('returns em dash for NaN', () => {
    expect(fmtNumber(NaN)).toBe('—')
  })

  it('returns em dash for Infinity', () => {
    expect(fmtNumber(Infinity)).toBe('—')
  })

  it('handles zero', () => {
    expect(fmtNumber(0)).toBe('0.0')
  })

  it('handles negative numbers', () => {
    expect(fmtNumber(-5.67)).toBe('-5.7')
  })
})

describe('fmtDuration', () => {
  it('formats seconds only', () => {
    expect(fmtDuration(45000)).toBe('45s')
  })

  it('formats minutes and seconds', () => {
    expect(fmtDuration(125000)).toBe('2m 5s')
  })

  it('formats hours, minutes, and seconds', () => {
    expect(fmtDuration(3661000)).toBe('1h 1m 1s')
  })

  it('handles zero', () => {
    expect(fmtDuration(0)).toBe('0s')
  })

  it('handles sub-second (floors to 0s)', () => {
    expect(fmtDuration(500)).toBe('0s')
  })
})

describe('fmtTimestamp', () => {
  it('returns a locale string', () => {
    const result = fmtTimestamp(1711900000000)
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })
})

describe('fmtDateShort', () => {
  it('returns a formatted date string', () => {
    const result = fmtDateShort(1711900000000)
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })
})

describe('fmtTemp', () => {
  it('returns em dash for sentinel -99', () => {
    expect(fmtTemp(-99)).toBe('—')
  })

  it('returns em dash for values <= -90', () => {
    expect(fmtTemp(-90)).toBe('—')
    expect(fmtTemp(-91)).toBe('—')
  })

  it('formats valid temperature in Celsius', () => {
    expect(fmtTemp(95)).toBe('95°C')
  })

  it('rounds to integer', () => {
    expect(fmtTemp(95.7)).toBe('96°C')
  })
})

describe('fmtSpeed', () => {
  it('formats speed in kph', () => {
    expect(fmtSpeed(120)).toBe('120 kph')
  })

  it('rounds to integer', () => {
    expect(fmtSpeed(120.6)).toBe('121 kph')
  })
})

describe('fmtPsi', () => {
  it('returns em dash for negative values', () => {
    expect(fmtPsi(-1)).toBe('—')
  })

  it('formats with 1 decimal + PSI unit', () => {
    expect(fmtPsi(15.34)).toBe('15.3 PSI')
  })

  it('handles zero', () => {
    expect(fmtPsi(0)).toBe('0.0 PSI')
  })
})
