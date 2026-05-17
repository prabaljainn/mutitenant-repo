// Self-service endpoints under /api/auth/me/* — edit own profile, change
// own password, list/revoke own refresh-token sessions.
//
// changePassword returns a fresh access + refresh pair because the call
// revokes all of the caller's existing sessions as a side-effect. The UI
// is expected to hand the pair to AuthProvider.persistSession so the
// current tab stays signed in.

import { api } from "./client";
import type { LoginResponse, SelfProfile, Session } from "./types";

export const meApi = {
  getProfile: async (): Promise<SelfProfile> => api<SelfProfile>("/api/auth/me/profile"),

  updateProfile: async (patch: {
    firstName?: string;
    lastName?: string;
  }): Promise<SelfProfile> => {
    const body: Record<string, string> = {};
    if (patch.firstName !== undefined) body.firstName = patch.firstName;
    if (patch.lastName !== undefined) body.lastName = patch.lastName;
    return api<SelfProfile>("/api/auth/me/profile", {
      method: "PATCH",
      json: body,
    });
  },

  changePassword: async (input: {
    currentPassword: string;
    newPassword: string;
  }): Promise<LoginResponse> => {
    return api<LoginResponse>("/api/auth/me/password", {
      method: "POST",
      json: input,
    });
  },

  listSessions: async (): Promise<Session[]> => {
    return api<Session[]>("/api/auth/me/sessions");
  },

  revokeSession: (id: string) =>
    api<void>(`/api/auth/me/sessions/${id}`, { method: "DELETE" }),
};

/**
 * Compute the same session id the backend exposes for a given raw refresh
 * token: 16 hex chars = first 64 bits of SHA-256. Used by the /account
 * Sessions UI to highlight the current row.
 *
 * Returns null in non-browser contexts (e.g. SSR pre-render) where
 * `crypto.subtle` is unavailable.
 */
export async function deriveSessionId(token: string | null): Promise<string | null> {
  if (!token || typeof crypto === "undefined" || !crypto.subtle) return null;
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  const bytes = new Uint8Array(buf);
  let hex = "";
  for (let i = 0; i < 8; i++) hex += bytes[i].toString(16).padStart(2, "0");
  return hex;
}
