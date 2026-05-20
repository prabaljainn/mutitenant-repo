// Cross-tenant user search. SUPPORT operators see only tenant users in
// their assigned tenants; ADMIN sees everything. Scoping is server-side.

import { api } from "./client";
import { toUserSearchResult, type SpringUserSearchResult } from "./adapters";
import type { UserSearchResult } from "./types";

export const usersSearchApi = {
  search: async (q: string, limit = 20): Promise<UserSearchResult[]> => {
    const params = new URLSearchParams({ q, limit: String(limit) });
    const rows = await api<SpringUserSearchResult[]>(
      `/admin/api/users/search?${params.toString()}`,
    );
    return rows.map(toUserSearchResult);
  },
};
