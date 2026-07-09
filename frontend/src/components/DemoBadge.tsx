export function DemoBadge({ label = 'SIMULATION' }: { label?: string }): JSX.Element {
  return (
    <span className="badge badge-demo" title="Seeded demo data — not real connector ingestion">
      {label}
    </span>
  );
}
