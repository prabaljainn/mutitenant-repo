// Translation layer between Spring's actual response shapes and the
// frontend-friendly DTOs the screens use. Keeps every adaptation in one
// place so when the backend gains the missing fields we can drop the
// adapter without touching the UI.

import type {
  Assignment,
  AuditRow,
  Member,
  MemberRole,
  Operator,
  OperatorRole,
  Tenant,
  UserKind,
  UserSearchResult,
} from "./types";
import { initials } from "@/lib/utils/initials";

// Raw Spring shapes — match TenantDtos.java / TenantSelfDtos.java exactly.

export type SpringTenant = {
  id: string;
  name: string;
  settings: Record<string, unknown>;
  ownerUserId: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

export type SpringTenantUser = {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  status: "INVITED" | "ACTIVE" | "SUSPENDED" | "DELETED";
  role: "ADMIN" | "MEMBER";
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type SpringOperator = {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  status: "INVITED" | "ACTIVE" | "SUSPENDED" | "DELETED";
  role: "OPERATOR_ADMIN" | "OPERATOR_SUPPORT";
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type SpringAssignment = {
  id: string;
  operatorUserId: string;
  tenantId: string;
  assignedBy: string;
  assignedAt: string;
};

// Spring's settings store envelope. The kind-specific fields live under
// `values` so the backend can stay generic across MQTT / DJI / future kinds.
export type SpringSettings = {
  tenantId: string;
  kind: "MQTT" | "DJI";
  configured: boolean;
  values: Record<string, unknown>;
  secrets: string[];
  lastTestedAt: string | null;
  lastTestOk: boolean | null;
  lastTestError: string | null;
  updatedAt: string;
};

// ─── Tenant ──────────────────────────────────────────────────────────────────

export function toTenant(s: SpringTenant): Tenant {
  return {
    id: s.id,
    name: s.name,
    mark: initials(s.name),
    ownerUserId: s.ownerUserId,
    userCount: null,                 // list endpoint doesn't include a count
    createdAt: s.createdAt,
  };
}

// ─── Member ──────────────────────────────────────────────────────────────────

const ROLE_TO_WIRE: Record<MemberRole, SpringTenantUser["role"]> = {
  Admin: "ADMIN",
  Member: "MEMBER",
};
const ROLE_FROM_WIRE: Record<SpringTenantUser["role"], MemberRole> = {
  ADMIN: "Admin",
  MEMBER: "Member",
};

export function memberRoleToWire(role: MemberRole): SpringTenantUser["role"] {
  return ROLE_TO_WIRE[role];
}

export function toMember(s: SpringTenantUser): Member {
  const name = [s.firstName, s.lastName].filter(Boolean).join(" ").trim() || s.email;
  return {
    userId: s.id,
    name,
    email: s.email,
    role: ROLE_FROM_WIRE[s.role],
    status: s.status,
    joinedAt: s.status === "INVITED" ? null : s.createdAt,
  };
}

// ─── Operator ────────────────────────────────────────────────────────────────

const OP_ROLE_TO_WIRE: Record<OperatorRole, SpringOperator["role"]> = {
  Admin: "OPERATOR_ADMIN",
  Support: "OPERATOR_SUPPORT",
};
const OP_ROLE_FROM_WIRE: Record<SpringOperator["role"], OperatorRole> = {
  OPERATOR_ADMIN: "Admin",
  OPERATOR_SUPPORT: "Support",
};

export function operatorRoleToWire(role: OperatorRole): SpringOperator["role"] {
  return OP_ROLE_TO_WIRE[role];
}

export function toOperator(s: SpringOperator): Operator {
  const name = [s.firstName, s.lastName].filter(Boolean).join(" ").trim() || s.email;
  return {
    id: s.id,
    email: s.email,
    firstName: s.firstName,
    lastName: s.lastName,
    name,
    role: OP_ROLE_FROM_WIRE[s.role],
    status: s.status,
    lastLoginAt: s.lastLoginAt,
    createdAt: s.createdAt,
  };
}

export function toAssignment(s: SpringAssignment): Assignment {
  return {
    id: s.id,
    operatorUserId: s.operatorUserId,
    tenantId: s.tenantId,
    assignedBy: s.assignedBy,
    assignedAt: s.assignedAt,
  };
}

// ─── User search ─────────────────────────────────────────────────────────────

export type SpringUserSearchResult = {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  kind: UserKind;
  tenantId: string | null;
  role: string | null;
  status: "INVITED" | "ACTIVE" | "SUSPENDED" | "DELETED";
  lastLoginAt: string | null;
};

export function toUserSearchResult(s: SpringUserSearchResult): UserSearchResult {
  const name = [s.firstName, s.lastName].filter(Boolean).join(" ").trim() || s.email;
  return {
    userId: s.userId,
    email: s.email,
    firstName: s.firstName,
    lastName: s.lastName,
    name,
    kind: s.kind,
    tenantId: s.tenantId,
    role: s.role,
    status: s.status,
    lastLoginAt: s.lastLoginAt,
  };
}

// ─── Audit ───────────────────────────────────────────────────────────────────

export type SpringAuditEntry = {
  id: string;
  timestamp: string;
  actorUserId: string | null;
  action: string;
  tenantId: string | null;
  metadata: Record<string, unknown> | null;
};

export function toAuditRow(s: SpringAuditEntry): AuditRow {
  return {
    id: s.id,
    timestamp: s.timestamp,
    actorUserId: s.actorUserId,
    action: s.action,
    tenantId: s.tenantId,
    metadata: s.metadata ?? {},
  };
}

// ─── Settings (MQTT / DJI) ───────────────────────────────────────────────────

import type { DjiSettings, MqttSettings } from "./types";

export function toMqtt(s: SpringSettings | null | undefined): MqttSettings {
  const v = (s?.values ?? {}) as Record<string, unknown>;
  return {
    host: String(v.host ?? ""),
    port: Number(v.port ?? 8883) || 8883,
    transport: (v.transport as MqttSettings["transport"]) || "tls",
    topicPrefix: String(v.topicPrefix ?? ""),
    username: String(v.username ?? ""),
  };
}

export function fromMqtt(m: MqttSettings): Record<string, unknown> {
  return {
    host: m.host,
    port: m.port,
    transport: m.transport,
    topicPrefix: m.topicPrefix,
    username: m.username,
  };
}

export function toDji(s: SpringSettings | null | undefined): DjiSettings {
  const v = (s?.values ?? {}) as Record<string, unknown>;
  return {
    region: (v.region as DjiSettings["region"]) || "ap",
    endpoint: String(v.endpoint ?? ""),
    appKey: String(v.appKey ?? ""),
    configured: !!s?.configured,
  };
}

export function fromDji(d: DjiSettings): Record<string, unknown> {
  return {
    region: d.region,
    endpoint: d.endpoint,
    appKey: d.appKey,
  };
}

