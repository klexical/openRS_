import { describe, it, expect } from 'vitest'
import {
  convertSpeed, speedUnit, fmtSpeed,
  convertDistance, distanceUnit, fmtDistance,
  convertTemp, tempUnit, fmtTemp,
  convertBoost, fmtBoost,
  convertTirePress, fmtTirePress,
  convertFuelEconomy, fuelEconomyUnit, fmtFuelEconomy,
} from './units'

describe('speed', () => {
  it('metric returns kph unchanged', () => {
    expect(convertSpeed(100, 'metric')).toBe(100)
  })

  it('imperial converts kph to mph', () => {
    expect(convertSpeed(100, 'imperial')).toBeCloseTo(62.14, 1)
  })

  it('speedUnit returns correct labels', () => {
    expect(speedUnit('metric')).toBe('KPH')
    expect(speedUnit('imperial')).toBe('MPH')
  })

  it('fmtSpeed formats with unit', () => {
    expect(fmtSpeed(100, 'metric')).toBe('100 KPH')
    expect(fmtSpeed(100, 'imperial')).toBe('62 MPH')
  })

  it('fmtSpeed handles non-finite', () => {
    expect(fmtSpeed(NaN, 'metric')).toBe('—')
    expect(fmtSpeed(Infinity, 'imperial')).toBe('—')
  })
})

describe('distance', () => {
  it('metric returns km unchanged', () => {
    expect(convertDistance(10, 'metric')).toBe(10)
  })

  it('imperial converts km to miles', () => {
    expect(convertDistance(10, 'imperial')).toBeCloseTo(6.21, 1)
  })

  it('fmtDistance formats with 1 decimal', () => {
    expect(fmtDistance(10, 'metric')).toBe('10.0 km')
    expect(fmtDistance(10, 'imperial')).toBe('6.2 mi')
  })

  it('distanceUnit returns correct labels', () => {
    expect(distanceUnit('metric')).toBe('km')
    expect(distanceUnit('imperial')).toBe('mi')
  })
})

describe('temperature', () => {
  it('metric returns Celsius unchanged', () => {
    expect(convertTemp(100, 'metric')).toBe(100)
  })

  it('imperial converts C to F', () => {
    expect(convertTemp(0, 'imperial')).toBe(32)
    expect(convertTemp(100, 'imperial')).toBe(212)
    expect(convertTemp(-40, 'imperial')).toBe(-40) // convergence point
  })

  it('fmtTemp handles sentinel -99', () => {
    expect(fmtTemp(-99, 'metric')).toBe('—')
    expect(fmtTemp(-99, 'imperial')).toBe('—')
  })

  it('fmtTemp formats with degree symbol', () => {
    expect(fmtTemp(95, 'metric')).toBe('95°C')
    expect(fmtTemp(95, 'imperial')).toBe('203°F')
  })

  it('tempUnit returns correct labels', () => {
    expect(tempUnit('metric')).toBe('°C')
    expect(tempUnit('imperial')).toBe('°F')
  })
})

describe('boost pressure', () => {
  it('PSI returns unchanged', () => {
    expect(convertBoost(15, 'PSI')).toBe(15)
  })

  it('converts PSI to bar', () => {
    // 15 PSI = ~1.034 bar
    expect(convertBoost(15, 'bar')).toBeCloseTo(1.034, 2)
  })

  it('converts PSI to kPa', () => {
    // 15 PSI = ~103.4 kPa
    expect(convertBoost(15, 'kPa')).toBeCloseTo(103.4, 0)
  })

  it('fmtBoost uses correct decimals per unit', () => {
    expect(fmtBoost(15, 'PSI')).toBe('15.0 PSI')
    expect(fmtBoost(15, 'bar')).toMatch(/^\d+\.\d{2} bar$/)
    expect(fmtBoost(15, 'kPa')).toMatch(/^\d+ kPa$/)
  })

  it('fmtBoost handles non-finite', () => {
    expect(fmtBoost(NaN, 'PSI')).toBe('—')
  })
})

describe('tire pressure', () => {
  it('PSI returns unchanged', () => {
    expect(convertTirePress(35, 'PSI')).toBe(35)
  })

  it('converts PSI to bar', () => {
    // 35 PSI = ~2.413 bar
    expect(convertTirePress(35, 'bar')).toBeCloseTo(2.413, 2)
  })

  it('converts PSI to kPa', () => {
    // 35 PSI = ~241.3 kPa
    expect(convertTirePress(35, 'kPa')).toBeCloseTo(241.3, 0)
  })

  it('fmtTirePress handles sentinel -1', () => {
    expect(fmtTirePress(-1, 'PSI')).toBe('—')
    expect(fmtTirePress(-1, 'bar')).toBe('—')
  })

  it('fmtTirePress formats correctly', () => {
    expect(fmtTirePress(35, 'PSI')).toBe('35.0 PSI')
  })
})

describe('fuel economy', () => {
  it('metric returns L/100km unchanged', () => {
    expect(convertFuelEconomy(8.5, 'metric')).toBe(8.5)
  })

  it('imperial converts L/100km to MPG', () => {
    // 8.5 L/100km = 235.215 / 8.5 ≈ 27.67 MPG
    expect(convertFuelEconomy(8.5, 'imperial')).toBeCloseTo(27.67, 1)
  })

  it('imperial handles zero L/100km', () => {
    expect(convertFuelEconomy(0, 'imperial')).toBe(0)
  })

  it('fuelEconomyUnit returns correct labels', () => {
    expect(fuelEconomyUnit('metric')).toBe('L/100km')
    expect(fuelEconomyUnit('imperial')).toBe('MPG')
  })

  it('fmtFuelEconomy handles zero and negative', () => {
    expect(fmtFuelEconomy(0, 'metric')).toBe('—')
    expect(fmtFuelEconomy(-1, 'imperial')).toBe('—')
  })

  it('fmtFuelEconomy formats with 1 decimal', () => {
    expect(fmtFuelEconomy(8.5, 'metric')).toBe('8.5 L/100km')
    expect(fmtFuelEconomy(8.5, 'imperial')).toBe('27.7 MPG')
  })
})
