export function Spinner({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-3 text-sm text-slate-600">
            <span className="h-4 w-4 animate-spin rounded-full border-2 border-line border-t-brand motion-reduce:animate-none" />
      <span>{label}</span>
    </div>
  )
}
