"use client";

import { type ReactNode } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Spinner } from "@/components/ui/Spinner";
import { NotImplementedError } from "@/lib/api/types";

/** Renders a friendly, copy-able "backend endpoint not yet implemented" message
 * when a query fails with NotImplementedError. Any other error or success falls
 * through to the caller's children. */
export function BackendStatus({
  isLoading,
  error,
  children,
  fallbackLabel = "Loading…",
}: {
  isLoading?: boolean;
  error?: unknown;
  children: ReactNode;
  fallbackLabel?: string;
}) {
  if (isLoading) {
    return (
      <div
        className="muted backend-status-loading"
        role="status"
        aria-live="polite"
        style={{
          display: "flex",
          alignItems: "center",
          gap: 10,
          padding: 16,
          fontSize: 13,
        }}
      >
        <Spinner size={14} />
        <span>{fallbackLabel}</span>
      </div>
    );
  }
  if (error instanceof NotImplementedError) {
    return (
      <div
        style={{
          display: "flex",
          alignItems: "flex-start",
          gap: 10,
          padding: 14,
          background: "color-mix(in oklab, var(--warn) 8%, transparent)",
          border: "1px solid color-mix(in oklab, var(--warn) 30%, transparent)",
          borderRadius: "var(--radius)",
          color: "var(--fg-2)",
          fontSize: 13,
        }}
      >
        <Icon d={Icons.zap} size={16} stroke={1.7} />
        <div>
          <div style={{ fontWeight: 600, color: "var(--fg)" }}>
            Backend endpoint not implemented yet
          </div>
          <div className="mono" style={{ marginTop: 4, fontSize: 11, color: "var(--fg-3)" }}>
            {error.path}
          </div>
          <div style={{ marginTop: 6 }}>
            The Spring platform doesn&apos;t expose this route yet. Wire it up to see live data here.
          </div>
        </div>
      </div>
    );
  }
  if (error) {
    const msg = error instanceof Error ? error.message : "Something went wrong.";
    return (
      <div className="muted" style={{ padding: 12, fontSize: 13, color: "var(--bad)" }}>
        {msg}
      </div>
    );
  }
  return <>{children}</>;
}
