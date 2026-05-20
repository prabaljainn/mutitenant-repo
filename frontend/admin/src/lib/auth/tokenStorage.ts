// Single source of truth for where the access + refresh tokens live in
// the browser. Pulling it out of AuthProvider means the account page
// (which derives the current session id from the refresh token) and
// any future call site read from the same keys without copy-pasting
// the string literals.
//
// Storage choice: localStorage, not sessionStorage. The trade-off:
//
//   - localStorage  — survives tab close, browser restart; shared
//                     across same-origin tabs. Operators stay signed
//                     in until they explicitly Sign out or the refresh
//                     token TTL (30 days) elapses.
//   - sessionStorage — per-tab, wiped on close. Every new tab and
//                     every browser restart bounces to /login.
//
// Both are equally JS-readable, so neither helps against XSS — the
// genuinely XSS-resistant option is HttpOnly cookies set by a Next
// route handler, which is a bigger refactor (CSRF, middleware-readable
// auth) and tracked separately. For an internal admin tool used by a
// handful of trusted operators, where the refresh token is already
// rotated every 15 min and revocable from the Account page, the UX
// win outweighs the marginal risk.

export const ACCESS_KEY = "cloudgcs.access";
export const REFRESH_KEY = "cloudgcs.refresh";

function storage(): Storage | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage;
  } catch {
    // Some embedded browsers (or strict cookie/storage policies) throw
    // on access. Treat as "no storage" — the user just won't persist
    // across reloads.
    return null;
  }
}

export function readTokens(): { access: string | null; refresh: string | null } {
  const s = storage();
  return {
    access: s?.getItem(ACCESS_KEY) ?? null,
    refresh: s?.getItem(REFRESH_KEY) ?? null,
  };
}

export function writeTokens(access: string, refresh: string): void {
  const s = storage();
  if (!s) return;
  try {
    s.setItem(ACCESS_KEY, access);
    s.setItem(REFRESH_KEY, refresh);
  } catch {
    // Quota / private-mode reject. Caller still keeps the tokens in
    // memory for this session — they'll just be lost on reload.
  }
}

export function clearTokens(): void {
  const s = storage();
  if (!s) return;
  try {
    s.removeItem(ACCESS_KEY);
    s.removeItem(REFRESH_KEY);
  } catch {
    // ignore
  }
}
