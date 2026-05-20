import { type CSSProperties } from "react";

export type SpinnerProps = {
  /** Pixel size of the spinner box. Defaults to 16 (good for inline). */
  size?: number;
  /** Override the accent colour. Defaults to currentColor so it inherits. */
  color?: string;
  /** Accessible label for screen readers. Visually hidden. */
  label?: string;
  className?: string;
  style?: CSSProperties;
};

/**
 * Minimal CSS-only spinner. Drawn with a single ::after on a thin
 * conic-gradient ring; no SVG, no images, no animation libraries.
 * Matches the rest of the admin's "no dependencies for primitives"
 * principle.
 */
export function Spinner({
  size = 16,
  color,
  label,
  className,
  style,
}: SpinnerProps) {
  return (
    <span
      className={"spinner " + (className ?? "")}
      style={{
        width: size,
        height: size,
        ...(color ? { color } : null),
        ...style,
      }}
      role="status"
      aria-live="polite"
    >
      {label && <span className="sr-only">{label}</span>}
    </span>
  );
}
