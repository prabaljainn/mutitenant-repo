"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";

import { BackendStatus } from "@/components/ui/EmptyState";
import { settingsApi } from "@/lib/api/settings";

import { DjiForm } from "./DjiForm";
import { MqttForm } from "./MqttForm";

export function SettingsCards({ tenantId }: { tenantId: string }) {
  const qc = useQueryClient();
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
    <div className="grid-2">
      <BackendStatus isLoading={mqtt.isLoading} error={mqtt.error}>
        <MqttForm
          tenantId={tenantId}
          initial={mqtt.data}
          onSaved={(next) => qc.setQueryData(["settings", tenantId, "mqtt"], next)}
        />
      </BackendStatus>
      <BackendStatus isLoading={dji.isLoading} error={dji.error}>
        <DjiForm
          tenantId={tenantId}
          initial={dji.data}
          onSaved={(next) => qc.setQueryData(["settings", tenantId, "dji"], next)}
        />
      </BackendStatus>
    </div>
  );
}
