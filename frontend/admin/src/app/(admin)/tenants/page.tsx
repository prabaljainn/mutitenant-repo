"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Topbar } from "@/components/shell/Topbar";
import { NewTenantModal } from "@/components/tenants/NewTenantModal";
import { BackendStatus } from "@/components/ui/EmptyState";
import { TenantMark } from "@/components/ui/TenantMark";
import { tenantsApi } from "@/lib/api/tenants";
import { useToast } from "@/lib/toast/ToastProvider";
import { formatGB } from "@/lib/utils/date";

export default function TenantsListPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const { notify } = useToast();
  const [q, setQ] = useState("");
  const [newOpen, setNewOpen] = useState(false);

  const tenants = useQuery({ queryKey: ["tenants"], queryFn: tenantsApi.list });

  const create = useMutation({
    mutationFn: (input: { name: string }) => tenantsApi.create(input),
    onSuccess: (created) => {
      qc.setQueryData(["tenants"], (prev: typeof tenants.data) => (prev ?? []).concat(created));
      qc.invalidateQueries({ queryKey: ["tenants", "count"] });
      setNewOpen(false);
      notify(`Tenant "${created.name}" created`, "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Failed to create tenant", "error");
    },
  });

  const filtered = (tenants.data ?? []).filter((t) => t.name.toLowerCase().includes(q.toLowerCase()));

  return (
    <>
      <Topbar crumbs={[{ label: "Admin", href: "/overview" }, { label: "Tenants" }]}>
        <button className="btn btn-primary" onClick={() => setNewOpen(true)}>
          <Icon d={Icons.plus} size={14} /> New tenant
        </button>
      </Topbar>
      <div className="page">
        <div className="page-head">
          <div>
            <div className="page-title">Tenants</div>
            <div className="page-sub">
              {tenants.data ? `${tenants.data.length} workspaces` : ""}
            </div>
          </div>
        </div>

        <BackendStatus isLoading={tenants.isLoading} error={tenants.error}>
          <div className="tbl-wrap">
            <div className="tbl-toolbar">
              <div
                className="tb-search"
                style={{ marginLeft: 0, width: 280, background: "var(--bg)" }}
              >
                <Icon d={Icons.search} size={14} />
                <input
                  value={q}
                  onChange={(e) => setQ(e.target.value)}
                  placeholder="Search tenants…"
                  style={{
                    flex: 1,
                    background: "transparent",
                    border: 0,
                    outline: 0,
                    color: "inherit",
                    fontFamily: "inherit",
                    fontSize: 12,
                  }}
                />
              </div>
              <div className="grow" />
            </div>
            <table className="tbl">
              <thead>
                <tr>
                  <th>Tenant</th>
                  <th style={{ textAlign: "right" }}>Users</th>
                  <th>Created</th>
                  <th style={{ width: 40 }}></th>
                </tr>
              </thead>
              <tbody>
                {filtered.length === 0 ? (
                  <tr>
                    <td
                      colSpan={4}
                      style={{ padding: 28, textAlign: "center", color: "var(--fg-3)" }}
                    >
                      {q ? `No tenants match "${q}".` : "No tenants yet."}
                    </td>
                  </tr>
                ) : (
                  filtered.map((tn) => (
                    <tr
                      key={tn.id}
                      style={{ cursor: "pointer" }}
                      onClick={() => router.push(`/tenants/${tn.id}`)}
                    >
                      <td>
                        <div className="user-cell">
                          <TenantMark mark={tn.mark} size={30} fontSize={11} />
                          <div>
                            <div className="user-cell-name">{tn.name}</div>
                            <div className="user-cell-email mono">{tn.id}</div>
                          </div>
                        </div>
                      </td>
                      <td className="mono" style={{ textAlign: "right" }}>
                        {tn.userCount ?? "—"}
                      </td>
                      <td className="mono muted">{formatGB(tn.createdAt)}</td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <button className="btn btn-ghost btn-icon btn-sm" aria-label="Row actions">
                          <Icon d={Icons.dots} size={14} />
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </BackendStatus>
      </div>

      <NewTenantModal
        open={newOpen}
        onClose={() => setNewOpen(false)}
        onSubmit={(input) => create.mutate(input)}
        submitting={create.isPending}
      />
    </>
  );
}
