"use client";

import { useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import {
  type Density,
  type SidebarMode,
  type TableMode,
  type Theme,
  useTheme,
} from "@/lib/theme/ThemeProvider";

// Dev-only floating panel that flips theme / density / sidebar / table on the
// fly. Lives behind NEXT_PUBLIC_ENABLE_TWEAKS — root layout decides whether to
// mount it at all.

const THEMES: { value: Theme; label: string }[] = [
  { value: "light", label: "Slate" },
  { value: "sand", label: "Sand" },
  { value: "forest", label: "Forest" },
  { value: "dark", label: "Midnight" },
  { value: "plum", label: "Plum" },
];
const DENSITIES: { value: Density; label: string }[] = [
  { value: "spacious", label: "Spacious" },
  { value: "balanced", label: "Balanced" },
  { value: "dense", label: "Dense" },
];
const SIDEBARS: { value: SidebarMode; label: string }[] = [
  { value: "labelled", label: "Labelled" },
  { value: "iconOnly", label: "Icon only" },
  { value: "collapsible", label: "Compact" },
];
const TABLES: { value: TableMode; label: string }[] = [
  { value: "lined", label: "Lined" },
  { value: "striped", label: "Striped" },
  { value: "card", label: "Card" },
];

export function TweaksPanel() {
  const { tweaks, set } = useTheme();
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        className="tweaks-fab"
        title="Tweaks (dev only)"
        aria-label="Open tweaks panel"
        onClick={() => setOpen((v) => !v)}
      >
        <Icon d={Icons.sliders} size={16} />
      </button>
      {open && (
        <div className="tweaks-panel" role="dialog" aria-label="Theme tweaks">
          <div className="tweaks-panel-head">
            <Icon d={Icons.sliders} size={14} />
            <span>Tweaks</span>
            <button
              className="btn btn-ghost btn-icon btn-sm"
              style={{ marginLeft: "auto" }}
              onClick={() => setOpen(false)}
              aria-label="Close"
            >
              <Icon d={Icons.x} size={12} />
            </button>
          </div>
          <div className="tweaks-panel-body">
            <Section title="Theme">
              <Radio label="Palette" options={THEMES} value={tweaks.theme} onChange={(v) => set("theme", v)} />
            </Section>
            <Section title="Layout">
              <Radio
                label="Density"
                options={DENSITIES}
                value={tweaks.density}
                onChange={(v) => set("density", v)}
              />
              <Radio
                label="Sidebar"
                options={SIDEBARS}
                value={tweaks.sidebar}
                onChange={(v) => set("sidebar", v)}
              />
              <Radio
                label="Tables"
                options={TABLES}
                value={tweaks.table}
                onChange={(v) => set("table", v)}
              />
            </Section>
          </div>
        </div>
      )}
    </>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="tweaks-section">
      <div className="tweaks-section-title">{title}</div>
      {children}
    </div>
  );
}

function Radio<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div className="tweaks-radio">
      <span className="tweaks-radio-label">{label}</span>
      <div className="tweaks-radio-row">
        {options.map((o) => (
          <button
            key={o.value}
            className="tweaks-radio-btn"
            aria-pressed={o.value === value}
            onClick={() => onChange(o.value)}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}
