"use client";

import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Topbar } from "@/components/shell/Topbar";
import { Chip } from "@/components/ui/Chip";
import { BackendStatus } from "@/components/ui/EmptyState";
import { auditApi, type AuditQuery } from "@/lib/api/audit";
import { operatorsApi } from "@/lib/api/operators";
import { tenantsApi } from "@/lib/api/tenants";
import type { AuditRow } from "@/lib/api/types";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isOperatorAdmin } from "@/lib/auth/jwt";
import { formatGB } from "@/lib/utils/date";

const PAGE_SIZE = 50;

// Coarse coloring of actions so the table reads at a glance.
function actionVariant(action: string): "good" | "warn" | "bad" | "info" | "muted" {
  if (action.includes("DELETED") || action.includes("ARCHIVED") || action.includes("SUSPENDED"))
    return "bad";
  if (action.includes("INVITED") || action.includes("RESENT") || action.includes("PASSWORD_RESET"))
    return "warn";
  if (action.includes("LOGIN_FAILURE")) return "bad";
  if (
    action.includes("CREATED") ||
    action.includes("TRANSFERRED") ||
    action.includes("PROVISIONED") ||
    action.includes("GRANTED")
  )
    return "good";
  return "muted";
}

function humanAction(action: string): string {
  return action.replace(/_/g, " ").toLowerCase();
}

export default function AuditPage() {
  const { claims } = useAuth();
  const canRead = isOperatorAdmin(claims);

  const [actorFilter, setActorFilter] = useState<string>("");
  const [tenantFilter, setTenantFilter] = useState<string>("");
  const [page, setPage] = useState(0);

  // Resets page when a filter changes so the user doesn't land on an
  // empty trailing page from a stale offset.
  function setFilter(kind: "actor" | "tenant", value: string) {
    if (kind === "actor") {
      setActorFilter(value);
      // The backend only accepts one filter at a time; clear the other so
      // the active filter is unambiguous.
      if (value) setTenantFilter("");
    } else {
      setTenantFilter(value);
      if (value) setActorFilter("");
    }
    setPage(0);
  }

  const query: AuditQuery = useMemo(
    () => ({
      actorUserId: actorFilter || undefined,
      tenantId: tenantFilter || undefined,
      page,
      size: PAGE_SIZE,
    }),
    [actorFilter, tenantFilter, page],
  );

  const rows = useQuery({
    queryKey: ["audit", query],
    queryFn: () => auditApi.list(query),
    enabled: canRead,
    // Audit doesn't change unless we refetch; keep results around briefly
    // when paging back so the table doesn't flash.
    staleTime: 10_000,
  });

  // Operators + tenants power the filter dropdowns and let us resolve
  // ids to human names in the table cells.
  const operators = useQuery({
    queryKey: ["operators", "for-audit"],
    queryFn: () => operatorsApi.list(),
    enabled: canRead,
  });
  const tenants = useQuery({
    queryKey: ["tenants"],
    queryFn: tenantsApi.list,
    enabled: canRead,
  });

  const operatorById = useMemo(
    () => new Map((operators.data ?? []).map((o) => [o.id, o])),
    [operators.data],
  );
  const tenantById = useMemo(
    () => new Map((tenants.data ?? []).map((t) => [t.id, t])),
    [tenants.data],
  );

  if (!canRead) {
    return (
      <>
        <Topbar crumbs={[{ label: "Admin", href: "/overview" }, { label: "Audit" }]} />
        <div className="page">
          <div className="muted" style={{ padding: 32, textAlign: "center" }}>
            Audit log is restricted to operator admins. Ask an admin if you need access.
          </div>
        </div>
      </>
    );
  }

  const list = rows.data ?? [];
  const atFirst = page === 0;
  const atLast = list.length < PAGE_SIZE;

  return (
    <>
      <Topbar crumbs={[{ label: "Admin", href: "/overview" }, { label: "Audit" }]} />
      <div className="page">
        <div className="page-head">
          <div>
            <div className="page-title">Audit log</div>
            <div className="page-sub">
              Newest first. One filter at a time — selecting actor clears tenant and vice versa.
            </div>
          </div>
        </div>

        <BackendStatus isLoading={rows.isLoading} error={rows.error}>
          <div className="tbl-wrap">
            <div className="tbl-toolbar">
              <div className="field" style={{ minWidth: 220 }}>
                <label className="field-label">Actor</label>
                <select
                  className="select"
                  value={actorFilter}
                  onChange={(e) => setFilter("actor", e.target.value)}
                >
                  <option value="">Any actor</option>
                  {(operators.data ?? []).map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name} ({o.email})
                    </option>
                  ))}
                </select>
              </div>
              <div className="field" style={{ minWidth: 220, marginLeft: 12 }}>
                <label className="field-label">Tenant</label>
                <select
                  className="select"
                  value={tenantFilter}
                  onChange={(e) => setFilter("tenant", e.target.value)}
                >
                  <option value="">Any tenant</option>
                  {(tenants.data ?? []).map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name} ({t.id})
                    </option>
                  ))}
                </select>
              </div>
              <div className="grow" />
              <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                <button
                  className="btn btn-ghost btn-icon btn-sm"
                  disabled={atFirst}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  title="Previous page"
                >
                  <Icon d={Icons.arrowL} size={14} />
                </button>
                <span className="mono muted" style={{ fontSize: 12 }}>
                  page {page + 1}
                </span>
                <button
                  className="btn btn-ghost btn-icon btn-sm"
                  disabled={atLast}
                  onClick={() => setPage((p) => p + 1)}
                  title="Next page"
                >
                  <Icon d={Icons.arrowR} size={14} />
                </button>
              </div>
            </div>
            <table className="tbl">
              <thead>
                <tr>
                  <th style={{ width: 180 }}>When</th>
                  <th>Actor</th>
                  <th>Action</th>
                  <th>Tenant</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {list.length === 0 ? (
                  <tr>
                    <td
                      colSpan={5}
                      style={{ padding: 28, textAlign: "center", color: "var(--fg-3)" }}
                    >
                      {atFirst ? "No audit rows match." : "No more entries."}
                    </td>
                  </tr>
                ) : (
                  list.map((r: AuditRow) => {
                    const op = r.actorUserId ? operatorById.get(r.actorUserId) : null;
                    const t = r.tenantId ? tenantById.get(r.tenantId) : null;
                    return (
                      <tr key={r.id}>
                        <td className="mono muted">{formatGB(r.timestamp)}</td>
                        <td>
                          {op ? (
                            <div>
                              <div className="user-cell-name">{op.name}</div>
                              <div className="user-cell-email">{op.email}</div>
                            </div>
                          ) : (
                            <span className="mono muted">{r.actorUserId ?? "system"}</span>
                          )}
                        </td>
                        <td>
                          <Chip variant={actionVariant(r.action)} dot={false}>
                            {humanAction(r.action)}
                          </Chip>
                        </td>
                        <td>
                          {t ? (
                            <div>
                              <div className="user-cell-name">{t.name}</div>
                              <div className="user-cell-email mono">{t.id}</div>
                            </div>
                          ) : (
                            <span className="muted">—</span>
                          )}
                        </td>
                        <td>
                          {Object.keys(r.metadata).length === 0 ? (
                            <span className="muted">—</span>
                          ) : (
                            <details>
                              <summary className="muted" style={{ cursor: "pointer", fontSize: 12 }}>
                                {Object.keys(r.metadata).length} field
                                {Object.keys(r.metadata).length === 1 ? "" : "s"}
                              </summary>
                              <pre
                                className="mono"
                                style={{
                                  fontSize: 11,
                                  marginTop: 6,
                                  padding: 8,
                                  background: "var(--bg)",
                                  border: "1px solid var(--border)",
                                  borderRadius: 4,
                                  overflowX: "auto",
                                  maxWidth: 360,
                                }}
                              >
                                {JSON.stringify(r.metadata, null, 2)}
                              </pre>
                            </details>
                          )}
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </BackendStatus>
      </div>
    </>
  );
}
