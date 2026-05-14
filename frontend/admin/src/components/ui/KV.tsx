import { type ReactNode } from "react";

export type KVItem = { label: string; value: ReactNode; mono?: boolean };

export function KV({ items }: { items: KVItem[] }) {
  return (
    <dl className="kv">
      {items.map((it) => (
        <div key={it.label} style={{ display: "contents" }}>
          <dt>{it.label}</dt>
          <dd className={it.mono ? "mono" : undefined}>{it.value}</dd>
        </div>
      ))}
    </dl>
  );
}
