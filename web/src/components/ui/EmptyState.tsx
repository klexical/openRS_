interface EmptyStateProps {
  icon: string
  title: string
  description: string
  action?: React.ReactNode
}

export function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-4 text-center">
      <span className="text-5xl opacity-30">{icon}</span>
      <h3 className="text-lg font-display tracking-wide text-frost">{title}</h3>
      <p className="text-sm text-dim max-w-md">{description}</p>
      {action}
    </div>
  )
}
