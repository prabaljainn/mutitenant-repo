"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";

import { type AccessClaims, decodeJwt, isExpired, isSuperAdmin } from "./jwt";
import { ACCESS_KEY, clearTokens, readTokens, writeTokens } from "./tokenStorage";

type AuthState = {
  accessToken: string | null;
  claims: AccessClaims | null;
  /**
   * "hydrating" is the transient state between mount and the
   * storage-rehydration effect running. Without it, every full
   * page refresh on an authenticated route briefly reports "anonymous"
   * (effect hasn't fired yet), the admin layout redirects to /login,
   * THEN hydration flips status to "authenticated", and the login page
   * bounces to /overview — losing whatever path the user actually
   * refreshed. Treat "hydrating" as "don't redirect yet".
   */
  status: "hydrating" | "anonymous" | "authenticating" | "authenticated";
};

type AuthCtx = AuthState & {
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** Refresh the access token via the backend, returning the new token or null. */
  refresh: () => Promise<string | null>;
  /**
   * Persist a token pair obtained from a non-login flow (accept-invite returns
   * tokens directly). Used by the accept-invite page to "log the user in"
   * after activation without an extra round-trip through /login.
   */
  persistSession: (accessToken: string, refreshToken: string) => void;
};

const Ctx = createContext<AuthCtx | null>(null);

// Orochiverse Spring login response shape: { accessToken, refreshToken, expiresIn, tokenType }.
type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [accessToken, setAccessToken] = useState<string | null>(null);
  // Start in "hydrating" — flipped to anonymous/authenticated by the
  // storage rehydration effect below. Keeps the admin layout from
  // prematurely redirecting to /login on a real-page refresh.
  const [status, setStatus] = useState<AuthState["status"]>("hydrating");

  // Refresh is kept out of React state — it's only ever read inside the
  // refresh() callback, never rendered. Using a ref keeps it out of the
  // dependency graph and avoids accidental re-renders on rotation.
  const refreshTokenRef = useRef<string | null>(null);

  // Hydrate from localStorage once at mount. See lib/auth/tokenStorage.ts
  // for the localStorage-vs-sessionStorage trade-off.
  useEffect(() => {
    if (typeof window === "undefined") return;
    const { access, refresh } = readTokens();
    if (access) {
      const claims = decodeJwt(access);
      if (claims && !isExpired(claims)) {
        setAccessToken(access);
        refreshTokenRef.current = refresh;
        setStatus("authenticated");
        return;
      }
      clearTokens();
    }
    setStatus("anonymous");
  }, []);

  const claims = useMemo<AccessClaims | null>(() => (accessToken ? decodeJwt(accessToken) : null), [accessToken]);

  const persistTokens = useCallback((at: string, rt: string) => {
    writeTokens(at, rt);
    refreshTokenRef.current = rt;
    setAccessToken(at);
  }, []);

  const signIn = useCallback(
    async (email: string, password: string) => {
      setStatus("authenticating");
      try {
        let res: Response;
        try {
          res = await fetch("/api/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ email, password }),
          });
        } catch {
          // Browser-level failure (offline, DNS, CORS preflight). The
          // backend never saw the request, so don't leak any
          // "credentials" framing into the message.
          throw new Error("Can't reach the platform. Check your connection and try again.");
        }
        if (!res.ok) {
          // The backend's AuthExceptionHandler returns a JSON envelope:
          //   { status, error, message, path, timestamp }
          // Map known error codes to user-facing copy that doesn't leak
          // backend internals — falling back to the server's `message`
          // for codes we haven't seen yet, and finally to a generic.
          const body = await readJson(res);
          throw new Error(translateAuthError(res.status, body));
        }
        const json = (await res.json()) as LoginResponse;
        const decoded = decodeJwt(json.accessToken);
        if (!isSuperAdmin(decoded)) {
          throw new Error("This account doesn't have admin-console access.");
        }
        persistTokens(json.accessToken, json.refreshToken);
        setStatus("authenticated");
      } catch (err) {
        setStatus("anonymous");
        throw err;
      }
    },
    [persistTokens]
  );

  const signOut = useCallback(async () => {
    const rt = refreshTokenRef.current;
    // Revoke the session server-side BEFORE clearing local state and
    // navigating — otherwise the page-unload races the fetch and the
    // refresh token stays alive in the platform's session store, which
    // is what causes "Active sessions" to accumulate one row per logout.
    // 2-second cap so a slow/dead backend can't trap the user in a
    // logged-in shell; revocation is still best-effort, but in the happy
    // path it actually lands.
    if (rt) {
      try {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 2000);
        await fetch("/api/auth/logout", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken: rt }),
          signal: controller.signal,
          keepalive: true,
        });
        clearTimeout(timer);
      } catch {
        // Swallow — local state still gets cleared below so the user
        // sees an immediate logout even if the platform is unreachable.
      }
    }
    clearTokens();
    refreshTokenRef.current = null;
    setAccessToken(null);
    setStatus("anonymous");
    router.replace("/login");
  }, [router]);

  // Cross-tab sync. With tokens in localStorage, two tabs share auth
  // state — but each tab keeps its own in-memory copy of the access
  // token and refresh-token ref. Without this listener, signing out
  // in tab A would leave tab B happily issuing requests with a token
  // the server has already revoked, and a refresh-rotation in tab A
  // (every ~15 min) would leave tab B holding the old refresh token,
  // 401-ing on next refresh, and forcibly logging out.
  //
  // The `storage` event fires only in OTHER tabs (not the one that
  // wrote), so we don't recurse.
  useEffect(() => {
    if (typeof window === "undefined") return;
    function onStorage(e: StorageEvent) {
      if (e.key !== ACCESS_KEY && e.key !== null) return;
      // newValue==null when the other tab signed out (or storage was
      // cleared). Mirror that here.
      if (!e.newValue) {
        refreshTokenRef.current = null;
        setAccessToken(null);
        setStatus("anonymous");
        return;
      }
      // newValue==access token from a sign-in or rotation in another
      // tab. Sync our in-memory copies so subsequent refresh calls use
      // the latest refresh token.
      const { access, refresh } = readTokens();
      if (access) {
        const claims = decodeJwt(access);
        if (claims && !isExpired(claims)) {
          setAccessToken(access);
          refreshTokenRef.current = refresh;
          setStatus("authenticated");
        }
      }
    }
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  // De-duplicate concurrent refresh calls. When the dashboard loads it fires
  // 3+ queries in parallel; each one that 401s would otherwise call /refresh
  // independently, stamping the refresh token thrice and hitting the per-token
  // rate limit on the backend. The pending-promise cache funnels every caller
  // to the same in-flight request.
  const refreshInFlight = useRef<Promise<string | null> | null>(null);
  const refresh = useCallback(async (): Promise<string | null> => {
    if (refreshInFlight.current) return refreshInFlight.current;
    const rt = refreshTokenRef.current;
    if (!rt) return null;
    const p = (async () => {
      try {
        const res = await fetch("/api/auth/refresh", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken: rt }),
        });
        if (!res.ok) {
          await signOut();
          return null;
        }
        const json = (await res.json()) as LoginResponse;
        persistTokens(json.accessToken, json.refreshToken);
        return json.accessToken;
      } catch {
        await signOut();
        return null;
      } finally {
        refreshInFlight.current = null;
      }
    })();
    refreshInFlight.current = p;
    return p;
  }, [persistTokens, signOut]);

  const persistSession = useCallback(
    (at: string, rt: string) => {
      persistTokens(at, rt);
      setStatus("authenticated");
    },
    [persistTokens]
  );

  const value: AuthCtx = { accessToken, claims, status, signIn, signOut, refresh, persistSession };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useAuth() must be used inside <AuthProvider>");
  return ctx;
}

type AuthErrorBody = { error?: string; message?: string };

async function readJson(res: Response): Promise<AuthErrorBody | null> {
  try {
    return (await res.json()) as AuthErrorBody;
  } catch {
    return null;
  }
}

/**
 * Map the platform's error envelope into the copy the login form should
 * actually show the user. Keeps the form clean of leaking JSON, paths,
 * or timestamps — and standardises wording for the few security-relevant
 * cases (credentials, rate-limit) regardless of which exact field the
 * backend objected to.
 */
function translateAuthError(status: number, body: AuthErrorBody | null): string {
  const code = body?.error;
  if (code === "invalid_credentials") return "Email or password is incorrect.";
  if (code === "rate_limited")
    return "Too many sign-in attempts. Please wait a few minutes and try again.";
  if (code === "operator_not_assigned")
    return "This account isn't allowed in the admin console.";
  if (code === "validation_failed") return "Please check your email and password and try again.";
  if (status === 401) return "Email or password is incorrect.";
  if (status === 403) return "This account doesn't have access to the admin console.";
  if (status === 429)
    return "Too many sign-in attempts. Please wait a few minutes and try again.";
  if (status >= 500) return "The platform is having trouble right now. Please try again shortly.";
  // Final fallback — prefer the server's prose if it set one, otherwise
  // a generic, never raw JSON.
  return body?.message?.trim() || "Sign-in failed. Please try again.";
}
