import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

interface Props {
  children: ReactNode
  fallbackLabel?: string
}

interface State {
  hasError: boolean
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
          <span className="text-3xl">⚠</span>
          <h2 className="text-sm font-display tracking-wide text-frost">
            {this.props.fallbackLabel ?? 'Something went wrong'}
          </h2>
          <p className="text-xs font-mono text-dim max-w-md">
            {this.state.error?.message ?? 'An unexpected error occurred while rendering this panel.'}
          </p>
          <button
            onClick={() => this.setState({ hasError: false, error: null })}
            className="px-4 py-2 rounded-md text-xs font-mono uppercase tracking-wider
                       text-accent border border-accent/20 hover:bg-accent/10 transition-colors"
          >
            Retry
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
