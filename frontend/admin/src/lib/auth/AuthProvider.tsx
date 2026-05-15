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

type AuthState = {
  accessToken: string | null;
  claims: AccessClaims | null;
  status: "anonymous" | "authenticating" | "authenticated";
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

const ACCESS_KEY = "cloudgcs.access";
const REFRESH_KEY = "cloudgcs.refresh";

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
  const [status, setStatus] = useState<AuthState["status"]>("anonymous");

  // Refresh is kept out of React state — it's only ever read inside the
  // refresh() callback, never rendered. Using a ref keeps it out of the
  // dependency graph and avoids accidental re-renders on rotation.
  const refreshTokenRef = useRef<string | null>(null);

  // Hydrate from sessionStorage once at mount. sessionStorage (not localStorage)
  // is deliberate — a closed tab logs the user out, matching the "in-memory"
  // policy from the design brief while still surviving page reloads during a
  // working session. The refresh token still gets sent to the backend on every
  // refresh call, so a stolen sessionStorage value rotates within ~15 min.
  useEffect(() => {
    if (typeof window === "undefined") return;
    const at = sessionStorage.getItem(ACCESS_KEY);
    const rt = sessionStorage.getItem(REFRESH_KEY);
    if (at) {
      const claims = decodeJwt(at);
      if (claims && !isExpired(claims)) {
        setAccessToken(at);
        refreshTokenRef.current = rt;
        setStatus("authenticated");
        return;
      }
      sessionStorage.removeItem(ACCESS_KEY);
      sessionStorage.removeItem(REFRESH_KEY);
    }
    setStatus("anonymous");
  }, []);

  const claims = useMemo<AccessClaims | null>(() => (accessToken ? decodeJwt(accessToken) : null), [accessToken]);

  const persistTokens = useCallback((at: string, rt: string) => {
    sessionStorage.setItem(ACCESS_KEY, at);
    sessionStorage.setItem(REFRESH_KEY, rt);
    refreshTokenRef.current = rt;
    setAccessToken(at);
  }, []);

  const signIn = useCallback(
    async (email: string, password: string) => {
      setStatus("authenticating");
      try {
        const res = await fetch("/api/auth/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email, password }),
        });
        if (!res.ok) {
          const txt = await res.text().catch(() => "");
          throw new Error(txt || `Login failed (${res.status})`);
        }
        const json = (await res.json()) as LoginResponse;
        const decoded = decodeJwt(json.accessToken);
        if (!isSuperAdmin(decoded)) {
          throw new Error("This account doesn't have admin access.");
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
    sessionStorage.removeItem(ACCESS_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    refreshTokenRef.current = null;
    setAccessToken(null);
    setStatus("anonymous");
    // Fire-and-forget; the server-side revocation is best-effort.
    if (rt) {
      void fetch("/api/auth/logout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: rt }),
      }).catch(() => {});
    }
    router.replace("/login");
  }, [router]);

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
