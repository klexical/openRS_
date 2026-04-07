import { useState, useEffect, useRef } from 'react'

interface SearchBarProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  debounceMs?: number
}

export function SearchBar({ value, onChange, placeholder = 'Search...', debounceMs = 200 }: SearchBarProps) {
  const [local, setLocal] = useState(value)
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined)

  useEffect(() => { setLocal(value) }, [value])

  const handleChange = (v: string) => {
    setLocal(v)
    clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => onChange(v), debounceMs)
  }

  return (
    <div className="relative">
      <input
        type="text"
        value={local}
        onChange={(e) => handleChange(e.target.value)}
        placeholder={placeholder}
        className="w-full px-3 py-2 rounded-md bg-surf3 border border-brd
                   text-xs font-mono text-frost placeholder:text-dim
                   focus:outline-none focus:border-accent/40 transition-colors"
      />
      {local && (
        <button
          onClick={() => { setLocal(''); onChange('') }}
          className="absolute right-2 top-1/2 -translate-y-1/2 text-dim hover:text-frost text-xs"
        >
          ✕
        </button>
      )}
    </div>
  )
}
