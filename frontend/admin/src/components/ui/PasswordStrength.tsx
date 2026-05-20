"use client";

import { useMemo } from "react";

import { estimatePassword, type StrengthScore } from "@/lib/utils/passwordStrength";

export type PasswordStrengthProps = {
  value: string;
  /** Optional confirmation value — when supplied, shows match/no-match line. */
  confirm?: string;
  /** Minimum acceptable length; drives the "too short" hint. Defaults to 8. */
  minLength?: number;
};

const SEGMENT_COUNT = 4;

/**
 * Strength meter + match line, used on accept-invite and reset-password.
 * Renders a 4-segment bar coloured by score and a one-line hint. When
 * {@link confirm} is supplied we also surface a green ✓ / red ✗ line so
 * the user gets immediate feedback without having to submit the form.
 */
export function PasswordStrength({ value, confirm, minLength = 8 }: PasswordStrengthProps) {
  const result = useMemo(() => estimatePassword(value), [value]);
  const tooShort = value.length > 0 && value.length < minLength;
  const filled = scoreToFilled(result.score);

  let hint = result.hint;
  if (tooShort) {
    hint = `At least ${minLength} characters required.`;
  }

  // Confirmation line: only show once the user has typed in BOTH fields.
  let matchNote: { kind: "ok" | "bad"; text: string } | null = null;
  if (confirm !== undefined && value.length > 0 && confirm.length > 0) {
    matchNote =
      confirm === value
        ? { kind: "ok", text: "Passwords match." }
        : { kind: "bad", text: "Passwords don't match yet." };
  }

  return (
    <div className="pw-strength" aria-live="polite">
      <div className="pw-strength-bars" data-score={result.score}>
        {Array.from({ length: SEGMENT_COUNT }, (_, i) => (
          <span
            key={i}
            className={"pw-strength-bar" + (i < filled ? " filled" : "")}
            aria-hidden
          />
        ))}
      </div>
      <div className="pw-strength-row">
        <span className="pw-strength-label">{tooShort ? "Too short" : result.label}</span>
        <span className="pw-strength-hint">{hint}</span>
      </div>
      {matchNote && (
        <div className={"pw-strength-match pw-strength-match-" + matchNote.kind}>
          {matchNote.text}
        </div>
      )}
    </div>
  );
}

function scoreToFilled(score: StrengthScore): number {
  // 0 → 0 filled, 1 → 1, 2 → 2, 3 → 3, 4 → 4. Matches SEGMENT_COUNT.
  return Math.min(SEGMENT_COUNT, Math.max(0, score));
}
