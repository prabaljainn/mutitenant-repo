// Per-tenant settings — Spring exposes a generic store at
// /admin/api/tenants/{id}/settings/{KIND}. We pivot between the
// envelope ({tenantId, kind, configured, values, …}) and the
// kind-specific MqttSettings / DjiSettings shapes the forms use.

import { api } from "./client";
import {
  fromDji,
  fromMqtt,
  toDji,
  toMqtt,
  type SpringSettings,
} from "./adapters";
import type { DjiSettings, MqttSettings } from "./types";

function path(tenantId: string, kind: "MQTT" | "DJI", suffix = "") {
  return `/admin/api/tenants/${tenantId}/settings/${kind}${suffix}`;
}

export const settingsApi = {
  mqtt: {
    get: async (tenantId: string): Promise<MqttSettings> => {
      const s = await api<SpringSettings>(path(tenantId, "MQTT"));
      return toMqtt(s);
    },
    save: async (tenantId: string, body: MqttSettings): Promise<MqttSettings> => {
      const s = await api<SpringSettings>(path(tenantId, "MQTT"), {
        method: "PUT",
        json: { values: fromMqtt(body) },
      });
      return toMqtt(s);
    },
    test: (tenantId: string) =>
      api<{ ok: boolean; latencyMs?: number; error?: string }>(
        path(tenantId, "MQTT", "/test"),
        { method: "POST", json: {} }
      ),
    clear: (tenantId: string) =>
      api<void>(path(tenantId, "MQTT"), { method: "DELETE" }),
  },
  dji: {
    get: async (tenantId: string): Promise<DjiSettings> => {
      const s = await api<SpringSettings>(path(tenantId, "DJI"));
      return toDji(s);
    },
    save: async (tenantId: string, body: DjiSettings): Promise<DjiSettings> => {
      const s = await api<SpringSettings>(path(tenantId, "DJI"), {
        method: "PUT",
        json: { values: fromDji(body) },
      });
      return toDji(s);
    },
    test: (tenantId: string) =>
      api<{ ok: boolean; latencyMs?: number; error?: string }>(
        path(tenantId, "DJI", "/test"),
        { method: "POST", json: {} }
      ),
    clear: (tenantId: string) =>
      api<void>(path(tenantId, "DJI"), { method: "DELETE" }),
  },
};
