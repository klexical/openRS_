// A part of Sapphire loaded when it is first shown rather than on the first visit (the 300 kB first-visit budget,
// #384). A tab left open across a deploy asks for a chunk that is gone: say so in plain words and keep the page.
import { lazy, Suspense, type ComponentType, type ReactNode } from 'react'

export function lazyChunk<P extends object>(load: () => Promise<ComponentType<P>>, what: string): ComponentType<P> {
  const Unloadable = () => (
    <p role="alert" className="a-notice">Sapphire was updated: reload this tab to open {what}.</p>
  )
  const Lazy = lazy(() => load().then((C) => ({ default: C }), (err: unknown) => {
    console.warn(`[Sapphire] ${what} could not load:`, err)
    return { default: Unloadable as unknown as ComponentType<P> }
  }))
  return function Loaded(props: P & { fallback?: ReactNode }) {
    const { fallback = null, ...rest } = props
    return <Suspense fallback={fallback}><Lazy {...(rest as P)} /></Suspense>
  }
}
