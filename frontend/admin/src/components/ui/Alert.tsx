import type { ReactNode } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";

export type AlertTone = "error" | "warn" | "info" | "success";

export type AlertProps = {
  tone?: AlertTone;
  children: ReactNode;
  /** Override the default tone icon. */
  iconPath?: string;
  className?: string;
};

const TONE_ICON: Record<AlertTone, string> = {
  error: Icons.x,
  warn: Icons.bell,
  info: Icons.bell,
  success: Icons.check,
};

/**
 * Inline alert banner. Used for form-level errors, success notices, and
 * caps-lock hints. Renders with `role="alert"` so screen readers announce
 * the message as soon as it appears — the auth forms rely on this to
 * keep keyboard-only users informed without grabbing focus.
 */
export function Alert({ tone = "error", children, iconPath, className }: AlertProps) {
  const path = iconPath ?? TONE_ICON[tone];
  const classes = `alert alert-${tone}${className ? ` ${className}` : ""}`;
  return (
    <div className={classes} role={tone === "error" ? "alert" : "status"} aria-live="polite">
      <span className="alert-icon">
        <Icon d={path} size={14} />
      </span>
      <div className="alert-body">{children}</div>
    </div>
  );
}
