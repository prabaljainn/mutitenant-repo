// Wire-level DTOs the admin frontend consumes. Mirrors the contract spelled
// out in the design brief. Where the Spring backend hasn't shipped the
// endpoint yet, the API client throws a NotImplementedError that the
// screens surface inline so backend gaps are visible rather than swallowed.

export type MemberRole = "Admin" | "Member";
export type MemberStatus = "ACTIVE" | "INVITED" | "SUSPENDED" | "DELETED";

export type OperatorRole = "Admin" | "Support";
export type OperatorStatus = "ACTIVE" | "INVITED" | "SUSPENDED" | "DELETED";

export type Operator = {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  name: string;            // firstName + lastName, joined
  role: OperatorRole;
  status: OperatorStatus;
  lastLoginAt: string | null;
  createdAt: string;
};

export type Assignment = {
  id: string;
  operatorUserId: string;
  tenantId: string;
  assignedBy: string;
  assignedAt: string;
};

export type Tenant = {
  id: string;
  name: string;
  mark: string;            // derived from name client-side; never on the wire
  ownerUserId: string | null;
  userCount: number | null; // null = "unknown" (the list endpoint doesn't carry it)
  createdAt: string;       // ISO
};

export type Member = {
  userId: string;
  name: string;            // firstName + lastName, joined
  email: string;
  role: MemberRole;
  status: MemberStatus;
  joinedAt: string | null; // backend createdAt; null when INVITED
};

export type MqttSettings = {
  host: string;
  port: number;
  transport: "tls" | "ws" | "tcp";
  topicPrefix: string;
  username: string;
};

export type DjiSettings = {
  region: "ap" | "us" | "eu";
  endpoint: string;
  appKey: string;
  configured: boolean;
};

export type DashboardStats = {
  tenants: number;
  users: number;
  pendingInvites: number;
};

export type ActivityRow = {
  actor: { name: string; email: string };
  verb: string;
  target: string;
  at: string; // ISO
};

/** A single row from the audit log. Action is the raw enum string from
 *  the backend (e.g. {@code TENANT_CREATED}); the UI maps it to a
 *  human-readable verb in the table. */
export type AuditRow = {
  id: string;
  timestamp: string;       // ISO
  actorUserId: string | null;
  action: string;
  tenantId: string | null;
  metadata: Record<string, unknown>;
};

export type UserKind = "OPERATOR" | "TENANT_USER";

/** One row from {@code /admin/api/users/search}. {@code tenantId} is null
 *  for operators; {@code role} carries the operator-role string for
 *  OPERATOR rows and the tenant-role string for TENANT_USER rows. */
export type UserSearchResult = {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  name: string;
  kind: UserKind;
  tenantId: string | null;
  role: string | null;
  status: "INVITED" | "ACTIVE" | "SUSPENDED" | "DELETED";
  lastLoginAt: string | null;
};

/** One active refresh-token session belonging to the current user.
 *  {@code id} is a stable, irreversible 16-hex SHA-256 prefix so the
 *  raw token never leaves the server. */
export type Session = {
  id: string;
  issuedAt: string;
  expiresAt: string;
};

/** Response from {@code GET /api/auth/me/profile} and PATCH equivalent. */
export type SelfProfile = {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
};

export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
};

export class ApiError extends Error {
  constructor(public status: number, message: string, public body?: unknown) {
    super(message);
    this.name = "ApiError";
  }
}

/** Raised when the backend returns 404/501 because the endpoint isn't built yet.
 * The UI catches these and renders a "Backend not implemented" inline notice with
 * the path so it's clear what's missing. */
export class NotImplementedError extends ApiError {
  constructor(public path: string) {
    super(501, `Backend endpoint ${path} is not implemented yet`);
    this.name = "NotImplementedError";
  }
}
