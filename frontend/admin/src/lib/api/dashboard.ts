// Spring exposes the dashboard counters at /admin/api/stats/overview.
// Recent activity is the tail of /admin/api/audit (admin-only) mapped
// into the simpler ActivityRow shape the overview ticker renders.

import { api } from "./client";
import { toAuditRow, type SpringAuditEntry } from "./adapters";
import type { ActivityRow, DashboardStats } from "./types";

type SpringOverview = {
  tenants: number;
  tenantUsers: number;
  pendingInvites: number;
};

function humanAction(a: string): string {
  return a.replace(/_/g, " ").toLowerCase();
}

export const dashboardApi = {
  stats: async (): Promise<DashboardStats> => {
    const raw = await api<SpringOverview>("/admin/api/stats/overview");
    return {
      tenants: raw.tenants,
      users: raw.tenantUsers,
      pendingInvites: raw.pendingInvites,
    };
  },
  recent: async (limit = 10): Promise<ActivityRow[]> => {
    const rows = await api<SpringAuditEntry[]>(`/admin/api/audit?size=${limit}`);
    return rows.map(toAuditRow).map((r) => ({
      // Overview doesn't have operators/tenants loaded — fall back to
      // raw ids. The dedicated /audit page resolves them properly.
      actor: {
        name: r.actorUserId ?? "system",
        email: "",
      },
      verb: humanAction(r.action),
      target: r.tenantId ?? "",
      at: r.timestamp,
    }));
  },
};
