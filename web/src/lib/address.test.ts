import { describe, it, expect } from 'vitest'
import { hrefOf, parseAddress } from './address'

// #416 ticket 2: a drive's address carries its view and its time range. `#/drive/<id>` stays the first view on the whole
// drive; anything not written the way Sapphire writes it reads as the first view on the whole drive (the page then puts
// the address right, in place: the integrator's call, 2026-11-18).

describe('#416 ticket 2: the address holds the view and the range', () => {
  it('a view and a range: read back as written', () => {
    expect(parseAddress('#/drive/a/trip?range=12000-47500')).toEqual({ place: 'drive', id: 'a', view: 'trip', range: { from: 12_000, to: 47_500 } })
    expect(parseAddress('#/drive/a/diagnostics')).toEqual({ place: 'drive', id: 'a', view: 'diagnostics', range: null })
    expect(parseAddress('#/drive/a?range=0-1000')).toEqual({ place: 'drive', id: 'a', view: 'dashboard', range: { from: 0, to: 1000 } })
  })

  it('the short form is the first view on the whole drive, both ways', () => {
    expect(parseAddress('#/drive/a')).toEqual({ place: 'drive', id: 'a', view: 'dashboard', range: null })
    expect(hrefOf({ place: 'drive', id: 'a' })).toBe('#/drive/a')
    expect(hrefOf({ place: 'drive', id: 'a', view: 'dashboard', range: null })).toBe('#/drive/a')
  })

  it('written by Sapphire: the view only when it is not the first, the range only when it is not the whole drive', () => {
    expect(hrefOf({ place: 'drive', id: 'a', view: 'trip', range: null })).toBe('#/drive/a/trip')
    expect(hrefOf({ place: 'drive', id: 'a', view: 'dashboard', range: { from: 5000, to: 9000 } })).toBe('#/drive/a?range=5000-9000')
    expect(hrefOf({ place: 'drive', id: 'a/b c', view: 'trip', range: { from: 0, to: 1 } })).toBe('#/drive/a%2Fb%20c/trip?range=0-1')
    expect(parseAddress('#/drive/a%2Fb%20c/trip?range=0-1')).toEqual({ place: 'drive', id: 'a/b c', view: 'trip', range: { from: 0, to: 1 } })
  })

  it('an unknown view or a garbled range: the first view, the whole drive (the page rewrites the address)', () => {
    expect(parseAddress('#/drive/a/engine')).toEqual({ place: 'drive', id: 'a', view: 'dashboard', range: null })
    expect(parseAddress('#/drive/a/trip?range=soon')).toEqual({ place: 'drive', id: 'a', view: 'trip', range: null })
    expect(parseAddress('#/drive/a/trip?zoom=1')).toEqual({ place: 'drive', id: 'a', view: 'trip', range: null })
    expect(hrefOf(parseAddress('#/drive/a/engine?range=x'))).toBe('#/drive/a')
  })

  it('the list and Settings are as before', () => {
    expect(parseAddress('#/')).toEqual({ place: 'drives' })
    expect(parseAddress('#/settings')).toEqual({ place: 'settings' })
    expect(parseAddress('#/drive/')).toEqual({ place: 'drives' })
    expect(parseAddress('#/drive/%E0%A4%A')).toEqual({ place: 'drives' })
  })
})
