"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";

import { BackendStatus } from "@/components/ui/EmptyState";
import { settingsApi } from "@/lib/api/settings";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isOperatorAdmin } from "@/lib/auth/jwt";

import { DjiForm } from "./DjiForm";
import { MqttForm } from "./MqttForm";
import { SettingsHistoryCard } from "./SettingsHistoryCard";

export function SettingsCards({
  tenantId,
  canManage = true,
}: {
  tenantId: string;
  canManage?: boolean;
}) {
  const qc = useQueryClient();
  const { claims } = useAuth();
  const mqtt = useQuery({
    queryKey: ["settings", tenantId, "mqtt"],
    queryFn: () => settingsApi.mqtt.get(tenantId),
    retry: false,
  });
  const dji = useQuery({
    queryKey: ["settings", tenantId, "dji"],
    queryFn: () => settingsApi.dji.get(tenantId),
    retry: false,
  });

  return (
    <>
      <div className="grid-2">
        <BackendStatus isLoading={mqtt.isLoading} error={mqtt.error}>
          <MqttForm
            tenantId={tenantId}
            initial={mqtt.data}
            canManage={canManage}
            onSaved={(next) => qc.setQueryData(["settings", tenantId, "mqtt"], next)}
          />
        </BackendStatus>
        <BackendStatus isLoading={dji.isLoading} error={dji.error}>
          <DjiForm
            tenantId={tenantId}
            initial={dji.data}
            canManage={canManage}
            onSaved={(next) => qc.setQueryData(["settings", tenantId, "dji"], next)}
          />
        </BackendStatus>
      </div>
      {/* Audit is ADMIN-only on the backend; render the history card only
          when the caller can actually read /admin/api/audit. SUPPORT
          would otherwise see a "Backend endpoint not implemented" error. */}
      {isOperatorAdmin(claims) && <SettingsHistoryCard tenantId={tenantId} />}
    </>
  );
}
