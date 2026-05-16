// Email-driven auth flows: accept-invite + reset-password. These hit the
// public Spring endpoints (no Authorization header needed — the single-use
// token in the request body IS the credential).

import type { LoginResponse } from "./types";

type ErrorBody = { message?: string; error?: string };

async function postPublic<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });

  if (res.status === 204) return null as T;

  if (!res.ok) {
    let parsed: ErrorBody | null = null;
    try {
      parsed = (await res.json()) as ErrorBody;
    } catch {
      // ignore — fall through to default message
    }
    throw new Error(parsed?.message || `Request failed (${res.status})`);
  }

  return (await res.json()) as T;
}

/** Activates an INVITED user and returns fresh tokens (auto-login). */
export function acceptInvite(token: string, newPassword: string): Promise<LoginResponse> {
  return postPublic<LoginResponse>("/api/auth/accept-invite", { token, newPassword });
}

/** Sets a new password from a forgot-password token. Returns void (204); no auto-login. */
export function resetPassword(token: string, newPassword: string): Promise<void> {
  return postPublic<void>("/api/auth/reset-password", { token, newPassword });
}

/**
 * Triggers a reset email if the address belongs to an active user.
 * Backend always returns 204 — no information leak about whether the
 * email exists. UI should always show the same generic confirmation.
 */
export function forgotPassword(email: string): Promise<void> {
  return postPublic<void>("/api/auth/forgot-password", { email });
}
