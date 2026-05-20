"use client";

export type TabDef<K extends string> = { key: K; label: string; count?: number | string };

export function Tabs<K extends string>({
  value,
  onChange,
  tabs,
}: {
  value: K;
  onChange: (key: K) => void;
  tabs: TabDef<K>[];
}) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <div
          key={t.key}
          role="tab"
          tabIndex={0}
          aria-selected={t.key === value}
          className={"tab" + (t.key === value ? " active" : "")}
          onClick={() => onChange(t.key)}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              onChange(t.key);
            }
          }}
        >
          {t.label}
          {t.count != null && <span className="count">{t.count}</span>}
        </div>
      ))}
    </div>
  );
}
