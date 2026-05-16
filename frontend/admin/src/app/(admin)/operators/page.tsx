"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { NewOperatorModal } from "@/components/operators/NewOperatorModal";
import { Topbar } from "@/components/shell/Topbar";
import { Avatar } from "@/components/ui/Avatar";
import { Chip } from "@/components/ui/Chip";
import { BackendStatus } from "@/components/ui/EmptyState";
import { operatorsApi } from "@/lib/api/operators";
import type { Operator, OperatorRole, OperatorStatus } from "@/lib/api/types";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isOperatorAdmin } from "@/lib/auth/jwt";
import { useToast } from "@/lib/toast/ToastProvider";
import { formatGB } from "@/lib/utils/date";

const STATUS_FILTERS: { key: OperatorStatus; label: string }[] = [
  { key: "ACTIVE", label: "Active" },
  { key: "INVITED", label: "Invited" },
  { key: "SUSPENDED", label: "Suspended" },
];

function statusChip(status: OperatorStatus) {
  switch (status) {
    case "ACTIVE":
      return <Chip variant="good">Active</Chip>;
    case "INVITED":
      return <Chip variant="warn">Invited</Chip>;
    case "SUSPENDED":
      return <Chip variant="bad">Suspended</Chip>;
    case "DELETED":
      return <Chip variant="muted">Deleted</Chip>;
  }
}

export default function OperatorsListPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const { notify } = useToast();
  const { claims } = useAuth();
  const canManage = isOperatorAdmin(claims);
  const [q, setQ] = useState("");
  const [statusFilter, setStatusFilter] = useState<OperatorStatus>("ACTIVE");
  const [newOpen, setNewOpen] = useState(false);

  const operators = useQuery({
    queryKey: ["operators", statusFilter],
    queryFn: () => operatorsApi.list(statusFilter),
  });

  const invite = useMutation({
    mutationFn: (input: {
      email: string;
      firstName: string;
      lastName: string;
      role: OperatorRole;
    }) => operatorsApi.invite(input),
    onSuccess: (created) => {
      // New invitees show up under the INVITED filter, not the current
      // ACTIVE view — invalidate both so whichever one the user flips to
      // is fresh.
      qc.invalidateQueries({ queryKey: ["operators"] });
      setNewOpen(false);
      notify(`Invitation sent to ${created.email}`, "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Invite failed", "error");
    },
  });

  const filtered = (operators.data ?? []).filter((o) =>
    [o.name, o.email].some((s) => s.toLowerCase().includes(q.toLowerCase())),
  );

  return (
    <>
      <Topbar crumbs={[{ label: "Admin", href: "/overview" }, { label: "Operators" }]}>
        {canManage && (
          <button className="btn btn-primary" onClick={() => setNewOpen(true)}>
            <Icon d={Icons.plus} size={14} /> Invite operator
          </button>
        )}
      </Topbar>
      <div className="page">
        <div className="page-head">
          <div>
            <div className="page-title">Operators</div>
            <div className="page-sub">
              {operators.data
                ? `${operators.data.length} ${statusFilter.toLowerCase()} operator${operators.data.length === 1 ? "" : "s"}`
                : ""}
            </div>
          </div>
        </div>

        <BackendStatus isLoading={operators.isLoading} error={operators.error}>
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
                  placeholder="Search operators…"
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
              <div
                style={{
                  display: "flex",
                  gap: 4,
                  marginLeft: 12,
                  border: "1px solid var(--border)",
                  borderRadius: 6,
                  padding: 4,
                }}
              >
                {STATUS_FILTERS.map((s) => (
                  <button
                    key={s.key}
                    className={
                      "btn btn-sm " +
                      (s.key === statusFilter ? "btn-primary" : "btn-ghost")
                    }
                    onClick={() => setStatusFilter(s.key)}
                  >
                    {s.label}
                  </button>
                ))}
              </div>
              <div className="grow" />
            </div>
            <table className="tbl">
              <thead>
                <tr>
                  <th>Operator</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Last login</th>
                  <th>Created</th>
                  <th style={{ width: 40 }}></th>
                </tr>
              </thead>
              <tbody>
                {filtered.length === 0 ? (
                  <tr>
                    <td
                      colSpan={6}
                      style={{ padding: 28, textAlign: "center", color: "var(--fg-3)" }}
                    >
                      {q
                        ? `No operators match "${q}".`
                        : `No ${statusFilter.toLowerCase()} operators.`}
                    </td>
                  </tr>
                ) : (
                  filtered.map((op: Operator) => (
                    <tr
                      key={op.id}
                      style={{ cursor: "pointer" }}
                      onClick={() => router.push(`/operators/${op.id}`)}
                    >
                      <td>
                        <div className="user-cell">
                          <Avatar name={op.name} />
                          <div>
                            <div className="user-cell-name">{op.name}</div>
                            <div className="user-cell-email">{op.email}</div>
                          </div>
                        </div>
                      </td>
                      <td>
                        <Chip variant={op.role === "Admin" ? "info" : "muted"} dot={false}>
                          {op.role}
                        </Chip>
                      </td>
                      <td>{statusChip(op.status)}</td>
                      <td className="mono muted">
                        {op.lastLoginAt ? formatGB(op.lastLoginAt) : "—"}
                      </td>
                      <td className="mono muted">{formatGB(op.createdAt)}</td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <button
                          className="btn btn-ghost btn-icon btn-sm"
                          aria-label="Row actions"
                        >
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

      <NewOperatorModal
        open={newOpen}
        onClose={() => setNewOpen(false)}
        onSubmit={(input) => invite.mutate(input)}
        submitting={invite.isPending}
      />
    </>
  );
}
