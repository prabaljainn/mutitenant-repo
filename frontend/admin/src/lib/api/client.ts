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

/**
 * One-time hook used by the toast layer so we can surface a single
 * "platform unreachable" toast when fetch itself throws — i.e. the
 * browser couldn't reach the server (offline, DNS failure, CORS
 * preflight reject, dev server stopped). Distinct from a 4xx/5xx
 * response, which carries its own error JSON the caller already shows.
 *
 * The handler is rate-limited inside this module so a burst of failing
 * queries on dashboard mount only fires once per cooldown window.
 */
type UnreachableHandler = (err: unknown) => void;
let unreachable: UnreachableHandler | null = null;
let lastUnreachableAt = 0;
const UNREACHABLE_COOLDOWN_MS = 10_000;
export function bindUnreachable(h: UnreachableHandler) {
  unreachable = h;
}
function reportUnreachable(err: unknown) {
  if (!unreachable) return;
  const now = Date.now();
  if (now - lastUnreachableAt < UNREACHABLE_COOLDOWN_MS) return;
  lastUnreachableAt = now;
  unreachable(err);
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

  let res: Response;
  try {
    res = await fetch(path, { ...init, headers });
  } catch (err) {
    // Browser-level failure — the request never reached the platform.
    // Surface once via the unreachable hook so the user gets one toast
    // instead of N "BackendStatus" empty panes, then rethrow as ApiError
    // so the query layer still records a failure.
    reportUnreachable(err);
    throw new ApiError(0, "Can't reach the platform.", null);
  }

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
