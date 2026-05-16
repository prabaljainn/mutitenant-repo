// Read-only audit log query. Spring exposes the surface at
// /admin/api/audit with paging + one of two filters (actor or tenant).
// Admin-only on the backend; the /audit page wraps the call with an
// admin gate so SUPPORT doesn't see the table either.

import { api } from "./client";
import { toAuditRow, type SpringAuditEntry } from "./adapters";
import type { AuditRow } from "./types";

export type AuditQuery = {
  actorUserId?: string;
  tenantId?: string;
  action?: string;
  /** ISO instant — inclusive lower bound. */
  since?: string;
  /** ISO instant — inclusive upper bound. */
  until?: string;
  page?: number;
  size?: number;
};

export const auditApi = {
  list: async (q: AuditQuery = {}): Promise<AuditRow[]> => {
    const params = new URLSearchParams();
    if (q.action) params.set("action", q.action);
    if (q.actorUserId) params.set("actorUserId", q.actorUserId);
    if (q.tenantId) params.set("tenantId", q.tenantId);
    if (q.since) params.set("since", q.since);
    if (q.until) params.set("until", q.until);
    if (q.page !== undefined) params.set("page", String(q.page));
    if (q.size !== undefined) params.set("size", String(q.size));
    const qs = params.toString();
    const path = "/admin/api/audit" + (qs ? `?${qs}` : "");
    const rows = await api<SpringAuditEntry[]>(path);
    return rows.map(toAuditRow);
  },
};

/** Mirrors AuditAction.java. Frontend uses this to populate the action
 *  filter dropdown. Grouped for readability; when adding a new enum
 *  case in the backend, add it here too. */
export const AUDIT_ACTIONS: readonly { group: string; values: string[] }[] = [
  {
    group: "Auth",
    values: [
      "LOGIN_SUCCESS",
      "LOGIN_FAILURE",
      "LOGOUT",
      "PASSWORD_RESET_REQUESTED",
      "PASSWORD_RESET_COMPLETED",
      "PASSWORD_CHANGED",
      "TENANT_SWITCHED",
      "TOKEN_REVOKED",
    ],
  },
  {
    group: "Tenants",
    values: [
      "TENANT_CREATED",
      "TENANT_UPDATED",
      "TENANT_ARCHIVED",
      "TENANT_DB_PROVISIONED",
      "TENANT_DB_DEPROVISIONED",
      "TENANT_OWNERSHIP_TRANSFERRED",
    ],
  },
  {
    group: "Operators",
    values: [
      "OPERATOR_INVITED",
      "OPERATOR_INVITE_RESENT",
      "OPERATOR_ROLE_CHANGED",
      "OPERATOR_SUSPENDED",
      "OPERATOR_DELETED",
      "OPERATOR_ASSIGNMENT_GRANTED",
      "OPERATOR_ASSIGNMENT_REVOKED",
    ],
  },
  {
    group: "Tenant users",
    values: [
      "TENANT_USER_INVITED",
      "TENANT_USER_INVITE_RESENT",
      "TENANT_USER_ROLE_CHANGED",
      "TENANT_USER_SUSPENDED",
      "TENANT_USER_DELETED",
    ],
  },
  {
    group: "Settings",
    values: [
      "TENANT_SETTING_UPDATED",
      "TENANT_SETTING_DELETED",
      "TENANT_SETTING_TESTED",
    ],
  },
];
