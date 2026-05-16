"use client";

import { useQuery } from "@tanstack/react-query";
import { type ReactNode } from "react";

import { operatorsApi } from "@/lib/api/operators";
import { tenantsApi } from "@/lib/api/tenants";
import { NotImplementedError } from "@/lib/api/types";
import { useTheme } from "@/lib/theme/ThemeProvider";

import { Sidebar } from "./Sidebar";

export function AdminShell({ children }: { children: ReactNode }) {
  const { tweaks } = useTheme();

  // Best-effort tenant count for the sidebar nav badge. Hidden if the backend
  // hasn't implemented the endpoint — better than rendering "Tenants 0".
  const { data: tenants } = useQuery({
    queryKey: ["tenants", "count"],
    queryFn: tenantsApi.list,
    select: (rows) => rows?.length ?? 0,
  });
  const { data: operators } = useQuery({
    queryKey: ["operators", "count"],
    queryFn: () => operatorsApi.list("ACTIVE"),
    select: (rows) => rows?.length ?? 0,
  });

  return (
    <div
      className="app-shell"
      data-theme={tweaks.theme}
      data-density={tweaks.density}
      data-sidebar={tweaks.sidebar}
      data-table={tweaks.table}
    >
      <Sidebar tenantCount={tenants} operatorCount={operators} />
      <div className="main">{children}</div>
    </div>
  );
}

// Re-export so callers don't import the type from React Query directly when
// they just want to check whether a fetch failed because the backend hasn't
// shipped the endpoint yet.
export { NotImplementedError };
