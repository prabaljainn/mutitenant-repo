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
  page?: number;
  size?: number;
};

export const auditApi = {
  list: async (q: AuditQuery = {}): Promise<AuditRow[]> => {
    const params = new URLSearchParams();
    if (q.actorUserId) params.set("actorUserId", q.actorUserId);
    if (q.tenantId) params.set("tenantId", q.tenantId);
    if (q.page !== undefined) params.set("page", String(q.page));
    if (q.size !== undefined) params.set("size", String(q.size));
    const qs = params.toString();
    const path = "/admin/api/audit" + (qs ? `?${qs}` : "");
    const rows = await api<SpringAuditEntry[]>(path);
    return rows.map(toAuditRow);
  },
};
