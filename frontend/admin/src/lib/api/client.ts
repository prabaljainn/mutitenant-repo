// Same-origin fetch wrapper. The frontend always calls /api/* on its own
// host; next.config.ts rewrites those paths to the Spring platform when
// PLATFORM_API_BASE is set, so we never deal with CORS in the browser.
//
// The wrapper:
//   - attaches `Authorization: Bearer <accessToken>` from a refresher callback
//     (avoids React Context here so it can be called from hooks AND from outside
//     the component tree, e.g. TanStack Query mutationFn)
//   - retries once on 401 after refreshing the token
//   - throws ApiError / NotImplementedError so screens can branch on status

import { ApiError, NotImplementedError } from "./types";

export type Tokens = {
  /** Latest access token (may be null pre-login). */
  getAccessToken: () => string | null;
  /** Asks the AuthProvider to rotate the access token via /api/auth/refresh. */
  refresh: () => Promise<string | null>;
  /** Force sign-out when refresh fails. */
  signOut: () => Promise<void>;
};

// The tokens hook is wired by AuthBridge at app-startup. Static accessor so we
// don't need to thread it through every fetch call.
let tokens: Tokens | null = null;
export function bindAuth(t: Tokens) {
  tokens = t;
}

export type RequestInit_ = RequestInit & { json?: unknown };

export async function api<T>(path: string, init: RequestInit_ = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.json !== undefined) {
    headers.set("Content-Type", "application/json");
    init.body = JSON.stringify(init.json);
  }
  const access = tokens?.getAccessToken();
  if (access) headers.set("Authorization", `Bearer ${access}`);

  let res = await fetch(path, { ...init, headers });

  // 401 → try to refresh once, then retry the original request.
  if (res.status === 401 && tokens) {
    const fresh = await tokens.refresh();
    if (fresh) {
      const retryHeaders = new Headers(init.headers);
      retryHeaders.set("Accept", "application/json");
      if (init.json !== undefined) retryHeaders.set("Content-Type", "application/json");
      retryHeaders.set("Authorization", `Bearer ${fresh}`);
      res = await fetch(path, { ...init, headers: retryHeaders });
    }
  }

  if (res.status === 404 || res.status === 501) {
    // Explicit signal for endpoints the Spring backend hasn't built yet.
    throw new NotImplementedError(path);
  }

  if (!res.ok) {
    let body: unknown;
    try {
      body = await res.json();
    } catch {
      body = await res.text().catch(() => null);
    }
    const msg = (body as { message?: string } | null)?.message || res.statusText;
    throw new ApiError(res.status, msg || `Request failed (${res.status})`, body);
  }

  // 204 → caller might still type it as T but we hand back null cast.
  if (res.status === 204) return null as T;
  return (await res.json()) as T;
}

export type { ApiError, NotImplementedError };
