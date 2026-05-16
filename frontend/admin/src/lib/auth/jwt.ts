// Lightweight JWT decoder. We do NOT verify the signature client-side —
// that's the backend's job. We just unpack the payload so the AuthProvider
// can show the right name/role and decide where to send the user.

export type AccessClaims = {
  sub: string;           // userId
  email?: string;
  iat: number;
  exp: number;
  iss?: string;
  // Orochiverse backend extras
  kind?: "OPERATOR" | "TENANT_USER";
  opRole?: "OPERATOR_ADMIN" | "OPERATOR_SUPPORT";
  tv?: number;           // token version
  activeTenantId?: string | null;
  // Design-spec aliases (older shape) — accept either.
  systemRole?: "SUPER_ADMIN" | "USER";
  tenantRoles?: string[];
};

export function decodeJwt(token: string): AccessClaims | null {
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = payload + "=".repeat((4 - (payload.length % 4)) % 4);
    const json =
      typeof window === "undefined"
        ? Buffer.from(padded, "base64").toString("utf-8")
        : atob(padded);
    return JSON.parse(json) as AccessClaims;
  } catch {
    return null;
  }
}

/** Returns true iff the decoded JWT is treated as a super-admin by either the
 * Orochiverse backend (`kind === 'OPERATOR'`) or the design-spec convention
 * (`systemRole === 'SUPER_ADMIN'`). The admin console refuses entry to anyone
 * else. */
export function isSuperAdmin(claims: AccessClaims | null): boolean {
  if (!claims) return false;
  return claims.kind === "OPERATOR" || claims.systemRole === "SUPER_ADMIN";
}

/** True only for {@code OPERATOR_ADMIN} — used to gate write controls
 *  (invite operator, change role/status, soft-delete, grant/revoke
 *  assignments). The backend already 403s SUPPORT on these endpoints;
 *  this helper just keeps the buttons out of the UI so SUPPORT doesn't
 *  click and get a toast. */
export function isOperatorAdmin(claims: AccessClaims | null): boolean {
  if (!claims) return false;
  return claims.kind === "OPERATOR" && claims.opRole === "OPERATOR_ADMIN";
}

export function isExpired(claims: AccessClaims | null, skewSeconds = 30): boolean {
  if (!claims) return true;
  const now = Math.floor(Date.now() / 1000);
  return claims.exp <= now + skewSeconds;
}
