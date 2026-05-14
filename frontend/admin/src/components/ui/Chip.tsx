import { type ReactNode } from "react";

type Variant = "good" | "warn" | "bad" | "info" | "muted";

export function Chip({
  variant = "muted",
  dot = true,
  children,
}: {
  variant?: Variant;
  dot?: boolean;
  children: ReactNode;
}) {
  return (
    <span className={`chip ${variant}`}>
      {dot && <span className="chip-dot" />}
      {children}
    </span>
  );
}
