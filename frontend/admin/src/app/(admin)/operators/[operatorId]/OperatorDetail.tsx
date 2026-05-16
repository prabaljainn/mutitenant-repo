"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { GrantAssignmentsModal } from "@/components/operators/GrantAssignmentsModal";
import { Topbar } from "@/components/shell/Topbar";
import { Avatar } from "@/components/ui/Avatar";
import { Card, CardBody, CardHead } from "@/components/ui/Card";
import { Chip } from "@/components/ui/Chip";
import { BackendStatus } from "@/components/ui/EmptyState";
import { KV } from "@/components/ui/KV";
import { assignmentsApi, operatorsApi } from "@/lib/api/operators";
import { tenantsApi } from "@/lib/api/tenants";
import type { Operator, OperatorRole, OperatorStatus } from "@/lib/api/types";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isOperatorAdmin } from "@/lib/auth/jwt";
import { useToast } from "@/lib/toast/ToastProvider";
import { formatGB } from "@/lib/utils/date";

const ROLES: OperatorRole[] = ["Admin", "Support"];

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

export function OperatorDetail({ operatorId }: { operatorId: string }) {
  const qc = useQueryClient();
  const { notify } = useToast();
  const { claims } = useAuth();
  const isSelf = claims?.sub === operatorId;
  // SUPPORT can view but not mutate. Backend already 403s these calls;
  // hiding the controls keeps the UI honest about what's actionable.
  const canManage = isOperatorAdmin(claims);

  const operator = useQuery({
    queryKey: ["operators", operatorId],
    queryFn: () => operatorsApi.get(operatorId),
  });

  const update = useMutation({
    mutationFn: (patch: {
      role?: OperatorRole;
      status?: OperatorStatus;
    }) => operatorsApi.update(operatorId, patch),
    onSuccess: (updated) => {
      qc.setQueryData(["operators", operatorId], updated);
      qc.invalidateQueries({ queryKey: ["operators"] });
      notify("Operator updated", "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Update failed", "error");
    },
  });

  const remove = useMutation({
    mutationFn: () => operatorsApi.remove(operatorId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["operators"] });
      notify("Operator deleted", "info");
      // Send the user back to the list — this page is now invalid.
      window.location.href = "/operators";
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Delete failed", "error");
    },
  });

  const resend = useMutation({
    mutationFn: () => operatorsApi.resendInvite(operatorId),
    onSuccess: () => {
      notify("Invite email re-sent", "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Resend failed", "error");
    },
  });

  function confirmDelete() {
    if (!window.confirm("Soft-delete this operator? Their access is revoked but audit history is preserved.")) return;
    remove.mutate();
  }

  if (operator.error || operator.isLoading) {
    return (
      <>
        <Topbar
          crumbs={[
            { label: "Admin", href: "/overview" },
            { label: "Operators", href: "/operators" },
            { label: operatorId },
          ]}
        />
        <div className="page">
          <BackendStatus isLoading={operator.isLoading} error={operator.error}>
            <div className="muted">Operator not found.</div>
          </BackendStatus>
        </div>
      </>
    );
  }

  const op = operator.data!;

  return (
    <>
      <Topbar
        crumbs={[
          { label: "Admin", href: "/overview" },
          { label: "Operators", href: "/operators" },
          { label: op.name },
        ]}
      />
      <div className="page">
        <div className="page-head" style={{ alignItems: "center" }}>
          <div className="row" style={{ gap: 14 }}>
            <Avatar name={op.name} size="lg" />
            <div>
              <div className="page-title">{op.name}</div>
              <div className="page-sub">{op.email}</div>
            </div>
          </div>
        </div>

        <div
          className={canManage ? "grid-2" : ""}
          style={canManage ? { gridTemplateColumns: "1fr 1fr" } : undefined}
        >
          <Card>
            <CardHead title="About" />
            <CardBody>
              <KV
                items={[
                  {
                    label: "Role",
                    value: canManage ? (
                      <OperatorRoleEditor
                        op={op}
                        onChange={(role) => update.mutate({ role })}
                        disabled={update.isPending || isSelf}
                      />
                    ) : (
                      <Chip variant={op.role === "Admin" ? "info" : "muted"} dot={false}>
                        {op.role}
                      </Chip>
                    ),
                  },
                  { label: "Status", value: statusChip(op.status) },
                  { label: "Last login", value: op.lastLoginAt ? formatGB(op.lastLoginAt) : "Never" },
                  { label: "Created", value: formatGB(op.createdAt), mono: true },
                  { label: "User ID", value: op.id, mono: true },
                ]}
              />
            </CardBody>
          </Card>

          {canManage && (
            <Card>
              <CardHead title="Actions" />
              <CardBody>
                <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                  {op.status === "ACTIVE" && (
                    <button
                      className="btn"
                      disabled={update.isPending || isSelf}
                      onClick={() => update.mutate({ status: "SUSPENDED" })}
                    >
                      <Icon d={Icons.shield} size={14} /> Suspend operator
                    </button>
                  )}
                  {op.status === "SUSPENDED" && (
                    <button
                      className="btn"
                      disabled={update.isPending}
                      onClick={() => update.mutate({ status: "ACTIVE" })}
                    >
                      <Icon d={Icons.shield} size={14} /> Reactivate operator
                    </button>
                  )}
                  {op.status === "INVITED" && (
                    <>
                      <button
                        className="btn"
                        disabled={resend.isPending}
                        onClick={() => resend.mutate()}
                      >
                        <Icon d={Icons.mail} size={14} />{" "}
                        {resend.isPending ? "Sending…" : "Resend invite email"}
                      </button>
                      <div className="field-hint">
                        Awaiting invite acceptance. Re-issuing invalidates the previous link.
                      </div>
                    </>
                  )}
                  <button
                    className="btn"
                    style={{ color: "var(--bad)" }}
                    disabled={remove.isPending || isSelf}
                    onClick={confirmDelete}
                    title={isSelf ? "You can't delete yourself" : undefined}
                  >
                    <Icon d={Icons.trash} size={14} /> Soft-delete operator
                  </button>
                  {isSelf && (
                    <div className="field-hint">
                      You can&rsquo;t change your own role, status, or delete yourself.
                    </div>
                  )}
                </div>
              </CardBody>
            </Card>
          )}
        </div>

        <AssignmentsCard operatorId={operatorId} canManage={canManage} />
      </div>
    </>
  );
}

function OperatorRoleEditor({
  op,
  onChange,
  disabled,
}: {
  op: Operator;
  onChange: (role: OperatorRole) => void;
  disabled?: boolean;
}) {
  return (
    <div
      style={{
        display: "inline-flex",
        gap: 4,
        border: "1px solid var(--border)",
        borderRadius: 6,
        padding: 3,
      }}
    >
      {ROLES.map((r) => (
        <button
          key={r}
          type="button"
          className={"btn btn-sm " + (r === op.role ? "btn-primary" : "btn-ghost")}
          style={{ padding: "2px 10px" }}
          disabled={disabled || r === op.role}
          onClick={() => onChange(r)}
        >
          {r}
        </button>
      ))}
    </div>
  );
}

function AssignmentsCard({
  operatorId,
  canManage,
}: {
  operatorId: string;
  canManage: boolean;
}) {
  const qc = useQueryClient();
  const { notify } = useToast();
  const assignments = useQuery({
    queryKey: ["operators", operatorId, "assignments"],
    queryFn: () => assignmentsApi.list(operatorId),
    retry: false,
  });
  // All tenants — used to (a) resolve tenantId → name and (b) populate the
  // grant modal with tenants the operator doesn't already have.
  const tenants = useQuery({
    queryKey: ["tenants"],
    queryFn: tenantsApi.list,
  });
  const [grantOpen, setGrantOpen] = useState(false);

  // Bulk grant: parallel POSTs, one per picked tenant. Any individual
  // failure surfaces a toast; successful ones still land. We refetch at
  // the end rather than optimistically rebuilding the cache to keep the
  // server view authoritative.
  const grant = useMutation({
    mutationFn: async (tenantIds: string[]) => {
      const results = await Promise.allSettled(
        tenantIds.map((id) => assignmentsApi.grant(operatorId, id)),
      );
      const failed = results.filter((r) => r.status === "rejected");
      const granted = tenantIds.length - failed.length;
      return { granted, failed: failed.length };
    },
    onSuccess: ({ granted, failed }) => {
      qc.invalidateQueries({ queryKey: ["operators", operatorId, "assignments"] });
      setGrantOpen(false);
      if (failed === 0) {
        notify(`Granted access to ${granted} tenant${granted === 1 ? "" : "s"}`, "success");
      } else {
        notify(
          `Granted ${granted}, ${failed} failed — see logs`,
          granted > 0 ? "info" : "error",
        );
      }
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Assignment failed", "error");
    },
  });

  const revoke = useMutation({
    mutationFn: (tenantId: string) => assignmentsApi.revoke(operatorId, tenantId),
    onMutate: async (tenantId) => {
      const prev = qc.getQueryData(["operators", operatorId, "assignments"]) as typeof assignments.data;
      qc.setQueryData(
        ["operators", operatorId, "assignments"],
        (rows: typeof assignments.data) =>
          (rows ?? []).filter((a) => a.tenantId !== tenantId),
      );
      return { prev };
    },
    onError: (e, _tid, ctx) => {
      if (ctx?.prev)
        qc.setQueryData(["operators", operatorId, "assignments"], ctx.prev);
      notify(e instanceof Error ? e.message : "Revoke failed", "error");
    },
    onSuccess: () => {
      notify("Assignment revoked", "info");
    },
  });

  const tenantById = new Map((tenants.data ?? []).map((t) => [t.id, t]));
  const assignedIds = new Set((assignments.data ?? []).map((a) => a.tenantId));
  const grantable = (tenants.data ?? []).filter((t) => !assignedIds.has(t.id));

  return (
    <div style={{ marginTop: 16 }}>
    <Card>
      <CardHead
        title="Tenant assignments"
        sub="Tenants this operator can act in. Operator-admins see every tenant regardless; assignments matter mainly for Support."
      />
      <BackendStatus isLoading={assignments.isLoading} error={assignments.error}>
        <table className="tbl">
          <thead>
            <tr>
              <th>Tenant</th>
              <th>Granted by</th>
              <th>Granted at</th>
              {canManage && <th style={{ width: 32 }}></th>}
            </tr>
          </thead>
          <tbody>
            {(assignments.data ?? []).length === 0 ? (
              <tr>
                <td colSpan={canManage ? 4 : 3} style={{ padding: 24, textAlign: "center", color: "var(--fg-3)" }}>
                  No tenant assignments yet.
                </td>
              </tr>
            ) : (
              (assignments.data ?? []).map((a) => {
                const t = tenantById.get(a.tenantId);
                return (
                  <tr key={a.id}>
                    <td>
                      <div className="user-cell-name">{t?.name ?? a.tenantId}</div>
                      <div className="user-cell-email mono">{a.tenantId}</div>
                    </td>
                    <td className="mono muted">{a.assignedBy}</td>
                    <td className="mono muted">{formatGB(a.assignedAt)}</td>
                    {canManage && (
                      <td>
                        <button
                          className="btn btn-ghost btn-icon btn-sm"
                          onClick={() => revoke.mutate(a.tenantId)}
                          title="Revoke"
                        >
                          <Icon d={Icons.trash} size={14} />
                        </button>
                      </td>
                    )}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </BackendStatus>
      {canManage && (
        <CardBody>
          <button
            className="btn btn-primary"
            disabled={grantable.length === 0 || grant.isPending}
            onClick={() => setGrantOpen(true)}
            title={grantable.length === 0 ? "All tenants already assigned" : undefined}
          >
            <Icon d={Icons.plus} size={14} /> Grant tenant access
          </button>
        </CardBody>
      )}
    </Card>

    {canManage && (
      <GrantAssignmentsModal
        open={grantOpen}
        grantable={grantable}
        onClose={() => setGrantOpen(false)}
        onSubmit={(ids) => grant.mutate(ids)}
        submitting={grant.isPending}
      />
    )}
    </div>
  );
}
