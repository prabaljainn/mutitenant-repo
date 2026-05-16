// Operators (cross-tenant Orochiverse staff) and their per-tenant
// assignments. Hits the real Spring endpoints under /admin/api/operators
// and adapts response shapes via adapters.ts.

import { api } from "./client";
import {
  operatorRoleToWire,
  toAssignment,
  toOperator,
  type SpringAssignment,
  type SpringOperator,
} from "./adapters";
import type {
  Assignment,
  Operator,
  OperatorRole,
  OperatorStatus,
} from "./types";

export const operatorsApi = {
  list: async (status?: OperatorStatus): Promise<Operator[]> => {
    const path = status
      ? `/admin/api/operators?status=${status}`
      : "/admin/api/operators";
    const rows = await api<SpringOperator[]>(path);
    return rows.map(toOperator);
  },
  get: async (id: string): Promise<Operator> => {
    const row = await api<SpringOperator>(`/admin/api/operators/${id}`);
    return toOperator(row);
  },
  invite: async (input: {
    email: string;
    firstName: string;
    lastName: string;
    role: OperatorRole;
  }): Promise<Operator> => {
    const row = await api<SpringOperator>("/admin/api/operators", {
      method: "POST",
      json: {
        email: input.email,
        firstName: input.firstName,
        lastName: input.lastName,
        role: operatorRoleToWire(input.role),
      },
    });
    return toOperator(row);
  },
  update: async (
    id: string,
    patch: {
      firstName?: string;
      lastName?: string;
      role?: OperatorRole;
      status?: OperatorStatus;
    },
  ): Promise<Operator> => {
    // Send only the fields the caller actually set so the partial-update
    // contract on the server side (null = leave alone) is preserved.
    const body: Record<string, unknown> = {};
    if (patch.firstName !== undefined) body.firstName = patch.firstName;
    if (patch.lastName !== undefined) body.lastName = patch.lastName;
    if (patch.role !== undefined) body.role = operatorRoleToWire(patch.role);
    if (patch.status !== undefined) body.status = patch.status;
    const row = await api<SpringOperator>(`/admin/api/operators/${id}`, {
      method: "PUT",
      json: body,
    });
    return toOperator(row);
  },
  remove: (id: string) =>
    api<void>(`/admin/api/operators/${id}`, { method: "DELETE" }),
  resendInvite: async (id: string): Promise<Operator> => {
    const row = await api<SpringOperator>(
      `/admin/api/operators/${id}/resend-invite`,
      { method: "POST", json: {} },
    );
    return toOperator(row);
  },
};

export const assignmentsApi = {
  list: async (operatorId: string): Promise<Assignment[]> => {
    const rows = await api<SpringAssignment[]>(
      `/admin/api/operators/${operatorId}/assignments`,
    );
    return rows.map(toAssignment);
  },
  grant: async (operatorId: string, tenantId: string): Promise<Assignment> => {
    const row = await api<SpringAssignment>(
      `/admin/api/operators/${operatorId}/assignments`,
      { method: "POST", json: { tenantId } },
    );
    return toAssignment(row);
  },
  revoke: (operatorId: string, tenantId: string) =>
    api<void>(`/admin/api/operators/${operatorId}/assignments/${tenantId}`, {
      method: "DELETE",
    }),
};
