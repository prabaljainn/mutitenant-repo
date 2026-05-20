"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { SettingsCards } from "@/components/settings/SettingsCards";
import { Topbar } from "@/components/shell/Topbar";
import { BackendStatus } from "@/components/ui/EmptyState";
import { TenantMark } from "@/components/ui/TenantMark";
import { tenantsApi } from "@/lib/api/tenants";

export default function GlobalSettingsPage() {
  const tenants = useQuery({ queryKey: ["tenants"], queryFn: tenantsApi.list });
  const [activeId, setActiveId] = useState<string | null>(null);

  useEffect(() => {
    if (!activeId && tenants.data && tenants.data.length > 0) {
      setActiveId(tenants.data[0].id);
    }
  }, [activeId, tenants.data]);

  const active = tenants.data?.find((t) => t.id === activeId) ?? null;

  return (
    <>
      <Topbar crumbs={[{ label: "Admin", href: "/overview" }, { label: "Settings" }]} />
      <div className="page">
        <div className="page-head">
          <div>
            <div className="page-title">Settings</div>
            <div className="page-sub">
              Per-tenant configuration. Pick a tenant to view or edit its settings.
            </div>
          </div>
        </div>

        <BackendStatus isLoading={tenants.isLoading} error={tenants.error}>
          {active && (
            <>
              <div className="settings-tenant-bar">
                <TenantMark mark={active.mark} size={40} fontSize={14} />
                <div className="settings-tenant-meta">
                  <div className="settings-tenant-eyebrow">Configuring</div>
                  <div className="settings-tenant-name">{active.name}</div>
                  <div className="settings-tenant-id mono">tenant · {active.id}</div>
                </div>
                <label className="settings-tenant-picker">
                  <span className="settings-tenant-picker-label">Switch tenant</span>
                  <select
                    className="select"
                    value={active.id}
                    onChange={(e) => setActiveId(e.target.value)}
                  >
                    {(tenants.data ?? []).map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <SettingsCards tenantId={active.id} />
            </>
          )}
          {tenants.data && tenants.data.length === 0 && (
            <div className="muted">No tenants yet. Create one from the Tenants tab.</div>
          )}
        </BackendStatus>
      </div>
    </>
  );
}
