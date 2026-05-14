"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";

import { Topbar } from "@/components/shell/Topbar";
import { Avatar } from "@/components/ui/Avatar";
import { BackendStatus } from "@/components/ui/EmptyState";
import { TenantMark } from "@/components/ui/TenantMark";
import { dashboardApi } from "@/lib/api/dashboard";
import { tenantsApi } from "@/lib/api/tenants";
import { NotImplementedError } from "@/lib/api/types";
import { formatRelative } from "@/lib/utils/date";

export default function OverviewPage() {
  const router = useRouter();
  const stats = useQuery({ queryKey: ["dashboard", "stats"], queryFn: dashboardApi.stats });
  const tenants = useQuery({ queryKey: ["tenants"], queryFn: tenantsApi.list });
  const recent = useQuery({ queryKey: ["dashboard", "recent"], queryFn: () => dashboardApi.recent(10) });

  return (
    <>
      <Topbar crumbs={[{ label: "Admin" }, { label: "Overview" }]} />
      <div className="page">
        <div className="page-head">
          <div>
            <div className="page-title">Overview</div>
            <div className="page-sub">A quick look across your tenants.</div>
          </div>
        </div>

        <div className="metrics" style={{ gridTemplateColumns: "repeat(3, 1fr)" }}>
          <Metric label="Tenants" value={stats.data?.tenants ?? tenants.data?.length ?? "—"} fallback={tenants.error instanceof NotImplementedError ? "—" : null} />
          <Metric label="Users" value={stats.data?.users ?? "—"} />
          <Metric label="Pending invites" value={stats.data?.pendingInvites ?? "—"} />
        </div>

        <div className="grid-2">
          <div className="card">
            <div className="card-head">
              <div className="card-title">Tenants</div>
              <Link
                href="/tenants"
                style={{ marginLeft: "auto", fontSize: 12, color: "var(--accent)", textDecoration: "none" }}
              >
                Manage →
              </Link>
            </div>
            <BackendStatus isLoading={tenants.isLoading} error={tenants.error}>
              <table className="tbl">
                <thead>
                  <tr>
                    <th>Tenant</th>
                    <th style={{ textAlign: "right" }}>Users</th>
                  </tr>
                </thead>
                <tbody>
                  {(tenants.data ?? []).map((tn) => (
                    <tr
                      key={tn.id}
                      style={{ cursor: "pointer" }}
                      onClick={() => router.push(`/tenants/${tn.id}`)}
                    >
                      <td>
                        <div className="user-cell">
                          <TenantMark mark={tn.mark} size={26} fontSize={10} />
                          <div className="user-cell-name">{tn.name}</div>
                        </div>
                      </td>
                      <td className="mono" style={{ textAlign: "right" }}>
                        {tn.userCount ?? "—"}
                      </td>
                    </tr>
                  ))}
                  {tenants.data && tenants.data.length === 0 && (
                    <tr>
                      <td colSpan={2} style={{ padding: 24, textAlign: "center", color: "var(--fg-3)" }}>
                        No tenants yet.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </BackendStatus>
          </div>

          <div className="card">
            <div className="card-head">
              <div className="card-title">Recent activity</div>
            </div>
            <BackendStatus isLoading={recent.isLoading} error={recent.error}>
              <div style={{ padding: "4px 8px" }}>
                {(recent.data ?? []).map((r, i, arr) => (
                  <div
                    key={i}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 10,
                      padding: "10px 8px",
                      borderBottom: i < arr.length - 1 ? "1px solid var(--border)" : 0,
                    }}
                  >
                    <Avatar name={r.actor.name} size="sm" />
                    <div style={{ flex: 1, fontSize: 13 }}>
                      <b>{r.actor.name}</b> <span className="muted">{r.verb}</span>{" "}
                      <span className="mono">{r.target}</span>
                    </div>
                    <div className="mono" style={{ fontSize: 11, color: "var(--fg-3)" }}>
                      {formatRelative(r.at)}
                    </div>
                  </div>
                ))}
                {recent.data && recent.data.length === 0 && (
                  <div style={{ padding: 16, color: "var(--fg-3)", fontSize: 13 }}>
                    No activity yet.
                  </div>
                )}
              </div>
            </BackendStatus>
          </div>
        </div>
      </div>
    </>
  );
}

function Metric({ label, value, fallback }: { label: string; value: number | string; fallback?: string | null }) {
  return (
    <div className="metric">
      <div className="metric-label">{label}</div>
      <div className="metric-value">{fallback ?? value}</div>
    </div>
  );
}
