"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState } from "react";

// useSearchParams() needs a Suspense boundary above the component or
// the Next.js 15 static-prerender pass errors. The page is auth-gated
// and renders nothing meaningful without a JWT, so fallback={null} is
// fine — the AuthBridge will swap in the real content the instant the
// client mounts.

import { MetadataView } from "@/components/audit/MetadataView";
import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Topbar } from "@/components/shell/Topbar";
import { Chip } from "@/components/ui/Chip";
import { BackendStatus } from "@/components/ui/EmptyState";
import { auditApi, AUDIT_ACTIONS, type AuditQuery } from "@/lib/api/audit";
import { operatorsApi } from "@/lib/api/operators";
import { tenantsApi } from "@/lib/api/tenants";
import type { AuditRow } from "@/lib/api/types";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isOperatorAdmin } from "@/lib/auth/jwt";
import { formatGB, formatTime } from "@/lib/utils/date";

const PAGE_SIZES = [25, 50, 100, 200] as const;
const DEFAULT_PAGE_SIZE = 50;

/** Shared column widths for the header table and the body table. Both
 *  must render the same colgroup so the dual-container layout stays
 *  aligned.
 *
 *  Percentages instead of px so the columns redistribute cleanly at
 *  any viewport width. Details is intentionally narrow — its cell
 *  only ever holds "N fields ▸" or "—"; the freed slack goes to the
 *  Actor/Action/Tenant columns where the long content lives.
 *
 *  Order: When / Actor / Action / Tenant / Details.
 *  No whitespace or comments allowed between {@code <col />} elements —
 *  React renders text nodes into the DOM and {@code <colgroup>} rejects
 *  non-{@code <col>} children, which trips the hydration check. */
function AuditColGroup() {
  return (
    <colgroup>
      <col style={{ width: "12%" }} />
      <col style={{ width: "28%" }} />
      <col style={{ width: "20%" }} />
      <col style={{ width: "20%" }} />
      <col style={{ width: "20%" }} />
    </colgroup>
  );
}

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
  return (
    <Suspense fallback={null}>
      <AuditPageImpl />
    </Suspense>
  );
}

function AuditPageImpl() {
  const router = useRouter();
  const search = useSearchParams();
  const { claims } = useAuth();
  const canRead = isOperatorAdmin(claims);

  // Deep-link seed: read filter values from the URL once at mount so
  // links like /audit?actorUserId=op-X pre-fill the filter. Subsequent
  // filter changes stay in component state (we don't sync back to URL).
  const [actorFilter, setActorFilter] = useState<string>(search?.get("actorUserId") ?? "");
  const [tenantFilter, setTenantFilter] = useState<string>(search?.get("tenantId") ?? "");
  const [actionFilter, setActionFilter] = useState<string>(search?.get("action") ?? "");
  // ISO date strings (YYYY-MM-DD) from <input type="date">. Converted to
  // full instants when serialized for the API call.
  const [sinceDate, setSinceDate] = useState<string>("");
  const [untilDate, setUntilDate] = useState<string>("");
  const [pageSize, setPageSize] = useState<number>(DEFAULT_PAGE_SIZE);
  const [page, setPage] = useState(0);

  // Backend takes one filter at a time. Setting any filter clears the
  // others and rewinds to page 0 so the user doesn't land on an empty
  // trailing page from a stale offset.
  function setFilter(kind: "actor" | "tenant" | "action", value: string) {
    if (kind === "actor") {
      setActorFilter(value);
      if (value) {
        setTenantFilter("");
        setActionFilter("");
      }
    } else if (kind === "tenant") {
      setTenantFilter(value);
      if (value) {
        setActorFilter("");
        setActionFilter("");
      }
    } else {
      setActionFilter(value);
      if (value) {
        setActorFilter("");
        setTenantFilter("");
      }
    }
    setPage(0);
  }

  const query: AuditQuery = useMemo(
    () => ({
      actorUserId: actorFilter || undefined,
      tenantId: tenantFilter || undefined,
      action: actionFilter || undefined,
      // Inclusive bounds: since = 00:00:00 of the picked day,
      // until = 23:59:59.999 so the chosen day is fully covered.
      since: sinceDate ? new Date(sinceDate + "T00:00:00").toISOString() : undefined,
      until: untilDate ? new Date(untilDate + "T23:59:59.999").toISOString() : undefined,
      page,
      size: pageSize,
    }),
    [actorFilter, tenantFilter, actionFilter, sinceDate, untilDate, page, pageSize],
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
  const atLast = list.length < pageSize;

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
            {/* Toolbar = filters only. Pagination + rows-per-page live
                in the footer beneath the table so the toolbar doesn't
                fight for horizontal space with the date pickers. */}
            <div className="tbl-toolbar">
              <div className="field" style={{ minWidth: 200 }}>
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
              <div className="field" style={{ minWidth: 200, marginLeft: 12 }}>
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
              <div className="field" style={{ minWidth: 200, marginLeft: 12 }}>
                <label className="field-label">Action</label>
                <select
                  className="select"
                  value={actionFilter}
                  onChange={(e) => setFilter("action", e.target.value)}
                >
                  <option value="">Any action</option>
                  {AUDIT_ACTIONS.map((g) => (
                    <optgroup key={g.group} label={g.group}>
                      {g.values.map((v) => (
                        <option key={v} value={v}>
                          {humanAction(v)}
                        </option>
                      ))}
                    </optgroup>
                  ))}
                </select>
              </div>
              <div className="field" style={{ minWidth: 140, marginLeft: 12 }}>
                <label className="field-label">From</label>
                <input
                  className="input"
                  type="date"
                  value={sinceDate}
                  onChange={(e) => {
                    setSinceDate(e.target.value);
                    setPage(0);
                  }}
                />
              </div>
              <div className="field" style={{ minWidth: 140, marginLeft: 12 }}>
                <label className="field-label">To</label>
                <input
                  className="input"
                  type="date"
                  value={untilDate}
                  onChange={(e) => {
                    setUntilDate(e.target.value);
                    setPage(0);
                  }}
                />
              </div>
              <div className="grow" />
            </div>

            {/* Dual-container table — header table on top, scrolling
                body table below. Both share the same <colgroup>; the
                first four columns are fixed-width and the last
                ("Details") absorbs any width difference. When the body
                shows a scrollbar, its table is ~15px narrower so the
                Details column shrinks by 15px; every other column
                boundary stays aligned with the header above. */}
            <div style={{ overflow: "hidden" }}>
              <table className="tbl" style={{ tableLayout: "fixed", width: "100%" }}>
                <AuditColGroup />
                <thead>
                  <tr>
                    <th>When</th>
                    <th>Actor</th>
                    <th>Action</th>
                    <th>Tenant</th>
                    <th>Details</th>
                  </tr>
                </thead>
              </table>
            </div>
            <div
              style={{
                // Reserve enough room for topbar + page head + toolbar +
                // header-row + footer + page padding. Tuned by eye so
                // the body fills the viewport without pushing the
                // footer off-screen on a typical 13–15" monitor.
                maxHeight: "calc(100vh - 340px)",
                overflowY: "auto",
              }}
            >
            <table className="tbl" style={{ tableLayout: "fixed", width: "100%" }}>
              <AuditColGroup />
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
                        <td className="mono muted">
                          <div>{formatGB(r.timestamp)}</div>
                          <div style={{ fontSize: 11, color: "var(--fg-3)" }}>
                            {formatTime(r.timestamp)}
                          </div>
                        </td>
                        <td>
                          {op ? (
                            <button
                              type="button"
                              className="btn btn-ghost mono"
                              style={{
                                padding: 0,
                                textAlign: "left",
                                background: "transparent",
                                border: 0,
                                cursor: "pointer",
                                fontSize: 12,
                              }}
                              onClick={() => router.push(`/operators/${op.id}`)}
                              title={`${op.name} — open operator`}
                            >
                              {op.email}
                            </button>
                          ) : (
                            <span className="mono muted">{r.actorUserId ?? "system"}</span>
                          )}
                        </td>
                        <td>
                          <button
                            type="button"
                            style={{
                              background: "transparent",
                              border: 0,
                              padding: 0,
                              cursor: "pointer",
                            }}
                            onClick={() => setFilter("action", r.action)}
                            title="Filter by this action"
                          >
                            <Chip variant={actionVariant(r.action)} dot={false}>
                              {humanAction(r.action)}
                            </Chip>
                          </button>
                        </td>
                        <td>
                          {r.tenantId ? (
                            <button
                              type="button"
                              className="btn btn-ghost mono"
                              style={{
                                padding: 0,
                                textAlign: "left",
                                background: "transparent",
                                border: 0,
                                cursor: "pointer",
                                fontSize: 12,
                              }}
                              onClick={() => router.push(`/tenants/${r.tenantId}`)}
                              title={t ? `Open ${t.name}` : "Open tenant"}
                            >
                              {r.tenantId}
                            </button>
                          ) : (
                            <span className="muted">—</span>
                          )}
                        </td>
                        <td>
                          {Object.keys(r.metadata).length === 0 ? (
                            <span className="muted">—</span>
                          ) : (
                            <details>
                              <summary
                                className="muted"
                                style={{ cursor: "pointer", fontSize: 12 }}
                              >
                                {Object.keys(r.metadata).length} field
                                {Object.keys(r.metadata).length === 1 ? "" : "s"}
                              </summary>
                              <div style={{ marginTop: 6 }}>
                                <MetadataView
                                  metadata={r.metadata}
                                  operatorById={operatorById}
                                  tenantById={tenantById}
                                />
                              </div>
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

            {/* Footer: rows-per-page + page nav. Stays in flow below
                the scrolling body so the toolbar can be filters-only. */}
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                padding: "4px 14px",
                borderTop: "1px solid var(--border)",
                fontSize: 12,
              }}
            >
              <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                <span className="muted">Rows per page</span>
                <select
                  className="select"
                  style={{ width: "auto", minWidth: 70 }}
                  value={pageSize}
                  onChange={(e) => {
                    setPageSize(Number(e.target.value));
                    setPage(0);
                  }}
                >
                  {PAGE_SIZES.map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>
              </div>
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                <button
                  className="btn btn-ghost btn-icon btn-sm"
                  disabled={atFirst}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  title="Previous page"
                >
                  <Icon d={Icons.arrowL} size={14} />
                </button>
                <span className="mono muted">page {page + 1}</span>
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
          </div>
        </BackendStatus>
      </div>
    </>
  );
}
