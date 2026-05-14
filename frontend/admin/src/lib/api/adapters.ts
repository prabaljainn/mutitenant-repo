// Translation layer between Spring's actual response shapes and the
// frontend-friendly DTOs the screens use. Keeps every adaptation in one
// place so when the backend gains the missing fields (mark, userCount,
// recent-activity, etc.) we can drop the adapter without touching the UI.

import type { Member, MemberRole, Tenant, TenantStatus } from "./types";
import { initials } from "@/lib/utils/initials";

// Raw Spring shapes — match TenantDtos.java / TenantSelfDtos.java exactly.

export type SpringTenant = {
  id: string;
  name: string;
  status: "TRIAL" | "ACTIVE" | "SUSPENDED" | "ARCHIVED";
  plan: string;
  settings: Record<string, unknown>;
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
  role: "TENANT_OWNER" | "ADMIN" | "EDITOR" | "VIEWER";
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
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
    plan: s.plan,
    status: s.status.toLowerCase() as TenantStatus,
    userCount: null,                 // list endpoint doesn't include a count
    createdAt: s.createdAt,
  };
}

// ─── Member ──────────────────────────────────────────────────────────────────

const ROLE_TO_WIRE: Record<MemberRole, SpringTenantUser["role"]> = {
  Owner: "TENANT_OWNER",
  Admin: "ADMIN",
  Editor: "EDITOR",
  Viewer: "VIEWER",
};
const ROLE_FROM_WIRE: Record<SpringTenantUser["role"], MemberRole> = {
  TENANT_OWNER: "Owner",
  ADMIN: "Admin",
  EDITOR: "Editor",
  VIEWER: "Viewer",
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

// ─── Tenant id slug ──────────────────────────────────────────────────────────

/** Default tenant id from a display name. Spring validates the slug against
 *  a regex (lowercase alphanumeric + hyphen), so we mirror that constraint.
 *  The user can override the suggestion in the New tenant modal. */
export function suggestTenantId(name: string): string {
  return name
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 24);
}
