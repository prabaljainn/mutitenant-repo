// Calls the real Spring endpoints exposed under /admin/api/* (the
// frontend's own `/admin/api/*` path is proxied through next.config.ts's
// rewrites). Every response goes through `adapters.ts` so the screens
// keep consuming the friendly Tenant / Member shapes.

import { api } from "./client";
import {
  memberRoleToWire,
  toMember,
  toTenant,
  type SpringTenant,
  type SpringTenantUser,
} from "./adapters";
import type { Member, MemberRole, MemberStatus, Tenant } from "./types";

export const tenantsApi = {
  list: async (): Promise<Tenant[]> => {
    const rows = await api<SpringTenant[]>("/admin/api/tenants");
    return rows.map(toTenant);
  },
  get: async (id: string): Promise<Tenant> => {
    const row = await api<SpringTenant>(`/admin/api/tenants/${id}`);
    return toTenant(row);
  },
  create: async (input: { name: string }): Promise<Tenant> => {
    const row = await api<SpringTenant>("/admin/api/tenants", {
      method: "POST",
      json: input,
    });
    return toTenant(row);
  },
  rename: async (id: string, name: string): Promise<Tenant> => {
    const row = await api<SpringTenant>(`/admin/api/tenants/${id}`, {
      method: "PUT",
      json: { name },
    });
    return toTenant(row);
  },
  remove: (id: string) =>
    api<void>(`/admin/api/tenants/${id}`, { method: "DELETE" }),
};

export const membersApi = {
  list: async (tenantId: string): Promise<Member[]> => {
    const rows = await api<SpringTenantUser[]>(`/admin/api/tenants/${tenantId}/users`);
    return rows.map(toMember);
  },
  invite: async (
    tenantId: string,
    input: { email: string; firstName: string; lastName: string; role: MemberRole }
  ): Promise<Member> => {
    const row = await api<SpringTenantUser>(`/admin/api/tenants/${tenantId}/users`, {
      method: "POST",
      json: {
        email: input.email,
        firstName: input.firstName,
        lastName: input.lastName,
        role: memberRoleToWire(input.role),
      },
    });
    return toMember(row);
  },
  update: async (
    tenantId: string,
    userId: string,
    patch: { role?: MemberRole; status?: MemberStatus },
  ): Promise<Member> => {
    // Send only the fields the caller actually changed — backend
    // contract: null = leave alone.
    const body: Record<string, unknown> = {};
    if (patch.role !== undefined) body.role = memberRoleToWire(patch.role);
    if (patch.status !== undefined) body.status = patch.status;
    const row = await api<SpringTenantUser>(
      `/admin/api/tenants/${tenantId}/users/${userId}`,
      { method: "PUT", json: body },
    );
    return toMember(row);
  },
  remove: (tenantId: string, userId: string) =>
    api<void>(`/admin/api/tenants/${tenantId}/users/${userId}`, { method: "DELETE" }),
};
