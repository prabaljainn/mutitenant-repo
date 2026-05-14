// Spring exposes the dashboard counters at /admin/api/stats/overview. The
// recent-activity feed isn't wired on the backend yet, so the API method
// keeps the same signature but routes to a path that will return 404 →
// NotImplementedError → the inline "Backend endpoint not implemented yet"
// notice in <BackendStatus>.

import { api } from "./client";
import type { ActivityRow, DashboardStats } from "./types";

type SpringOverview = {
  tenants: number;
  tenantUsers: number;
  pendingInvites: number;
};

export const dashboardApi = {
  stats: async (): Promise<DashboardStats> => {
    const raw = await api<SpringOverview>("/admin/api/stats/overview");
    return {
      tenants: raw.tenants,
      users: raw.tenantUsers,
      pendingInvites: raw.pendingInvites,
    };
  },
  recent: (limit = 10) =>
    api<ActivityRow[]>(`/admin/api/audit/recent?limit=${limit}`),
};
