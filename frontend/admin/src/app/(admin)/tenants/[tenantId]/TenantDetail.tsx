"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type KeyboardEvent } from "react";

import { SettingsCards } from "@/components/settings/SettingsCards";
import { Topbar } from "@/components/shell/Topbar";
import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Avatar } from "@/components/ui/Avatar";
import { BackendStatus } from "@/components/ui/EmptyState";
import { Card, CardBody, CardHead } from "@/components/ui/Card";
import { Chip } from "@/components/ui/Chip";
import { KV } from "@/components/ui/KV";
import { Tabs, type TabDef } from "@/components/ui/Tabs";
import { TenantMark } from "@/components/ui/TenantMark";
import { membersApi, tenantsApi } from "@/lib/api/tenants";
import { type MemberRole, type Tenant } from "@/lib/api/types";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isOperatorAdmin } from "@/lib/auth/jwt";
import { useToast } from "@/lib/toast/ToastProvider";
import { formatGB } from "@/lib/utils/date";

type TabKey = "overview" | "members" | "settings";

const INVITE_ROLES: MemberRole[] = ["Admin", "Member"];

export function TenantDetail({ tenantId }: { tenantId: string }) {
  const qc = useQueryClient();
  const { notify } = useToast();
  const { claims } = useAuth();
  const canManage = isOperatorAdmin(claims);
  const tenant = useQuery({ queryKey: ["tenants", tenantId], queryFn: () => tenantsApi.get(tenantId) });
  const members = useQuery({
    queryKey: ["tenants", tenantId, "members"],
    queryFn: () => membersApi.list(tenantId),
    retry: false,
  });

  const [tab, setTab] = useState<TabKey>("members");
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState("");

  useEffect(() => {
    if (tenant.data) setNameDraft(tenant.data.name);
  }, [tenant.data]);

  const rename = useMutation({
    mutationFn: (name: string) => tenantsApi.rename(tenantId, name),
    onSuccess: (updated) => {
      qc.setQueryData(["tenants", tenantId], updated);
      qc.setQueryData(["tenants"], (prev: Tenant[] | undefined) =>
        prev?.map((t) => (t.id === updated.id ? updated : t)) ?? prev
      );
      setRenaming(false);
      notify("Tenant renamed", "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Rename failed", "error");
      setRenaming(false);
    },
  });

  function commitRename() {
    const v = nameDraft.trim();
    if (!tenant.data || !v || v === tenant.data.name) {
      setRenaming(false);
      setNameDraft(tenant.data?.name ?? "");
      return;
    }
    rename.mutate(v);
  }

  if (tenant.error || tenant.isLoading) {
    return (
      <>
        <Topbar
          crumbs={[
            { label: "Admin", href: "/overview" },
            { label: "Tenants", href: "/tenants" },
            { label: tenantId },
          ]}
        />
        <div className="page">
          <BackendStatus isLoading={tenant.isLoading} error={tenant.error}>
            <div className="muted">Tenant not found.</div>
          </BackendStatus>
        </div>
      </>
    );
  }

  const t = tenant.data!;
  const memberCount = members.data?.length ?? 0;
  const ownerName = members.data?.find((m) => m.userId === t.ownerUserId)?.name ?? null;

  const tabs: TabDef<TabKey>[] = [
    { key: "overview", label: "Overview" },
    { key: "members", label: "Members", count: memberCount },
    { key: "settings", label: "Settings" },
  ];

  return (
    <>
      <Topbar
        crumbs={[
          { label: "Admin", href: "/overview" },
          { label: "Tenants", href: "/tenants" },
          { label: t.name },
        ]}
      >
        <button
          className="btn"
          onClick={() => notify(`Switched into ${t.name}`, "info")}
        >
          <Icon d={Icons.switch} size={14} /> Switch into
        </button>
      </Topbar>
      <div className="page">
        <div className="page-head" style={{ alignItems: "center" }}>
          <div className="row" style={{ gap: 14 }}>
            <TenantMark mark={t.mark} size={48} radius={10} fontSize={16} />
            <div>
              {renaming && canManage ? (
                <input
                  autoFocus
                  className="input"
                  style={{ fontSize: 22, height: 36, fontWeight: 600, padding: "0 8px", maxWidth: 360 }}
                  value={nameDraft}
                  onChange={(e) => setNameDraft(e.target.value)}
                  onBlur={commitRename}
                  onKeyDown={(e: KeyboardEvent<HTMLInputElement>) => {
                    if (e.key === "Enter") commitRename();
                    if (e.key === "Escape") {
                      setRenaming(false);
                      setNameDraft(t.name);
                    }
                  }}
                />
              ) : (
                <div
                  className="page-title"
                  onDoubleClick={canManage ? () => setRenaming(true) : undefined}
                  style={canManage ? { cursor: "text" } : undefined}
                >
                  {t.name}
                  {canManage && (
                    <button
                      className="btn btn-ghost btn-icon btn-sm"
                      style={{ marginLeft: 6, verticalAlign: "middle" }}
                      onClick={() => setRenaming(true)}
                      title="Rename tenant"
                    >
                      <Icon d={Icons.edit} size={13} />
                    </button>
                  )}
                </div>
              )}
              <div className="page-sub mono">{t.id}</div>
            </div>
          </div>
        </div>

        <Tabs<TabKey> value={tab} onChange={setTab} tabs={tabs} />

        {tab === "overview" && (
          <div className="grid-2">
            <Card>
              <CardHead title="About" />
              <CardBody>
                <KV
                  items={[
                    { label: "Owner", value: ownerName ?? (t.ownerUserId ? t.ownerUserId : "— not yet assigned") },
                    { label: "Members", value: memberCount },
                    { label: "Created", value: formatGB(t.createdAt), mono: true },
                    { label: "Tenant ID", value: t.id, mono: true },
                  ]}
                />
              </CardBody>
            </Card>
            <Card>
              <CardHead title="Quick actions" />
              <CardBody>
                <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                  <button className="btn" onClick={() => setTab("members")}>
                    <Icon d={Icons.users} size={14} /> Manage members
                  </button>
                  <button className="btn" onClick={() => setTab("settings")}>
                    <Icon d={Icons.settings} size={14} /> Tenant settings
                  </button>
                  <button className="btn" onClick={() => notify(`Switched into ${t.name}`, "info")}>
                    <Icon d={Icons.switch} size={14} /> Switch into tenant
                  </button>
                </div>
              </CardBody>
            </Card>
          </div>
        )}

        {tab === "members" && (
          <MembersTab tenantId={tenantId} ownerUserId={t.ownerUserId} canManage={canManage} />
        )}

        {tab === "settings" && <SettingsCards tenantId={tenantId} canManage={canManage} />}
      </div>
    </>
  );
}

function MembersTab({
  tenantId,
  ownerUserId,
  canManage,
}: {
  tenantId: string;
  ownerUserId: string | null;
  canManage: boolean;
}) {
  const qc = useQueryClient();
  const { notify } = useToast();
  const members = useQuery({
    queryKey: ["tenants", tenantId, "members"],
    queryFn: () => membersApi.list(tenantId),
    retry: false,
  });
  const [email, setEmail] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [role, setRole] = useState<MemberRole>("Member");
  const [err, setErr] = useState("");

  const invite = useMutation({
    mutationFn: (input: { email: string; firstName: string; lastName: string; role: MemberRole }) =>
      membersApi.invite(tenantId, input),
    onSuccess: (created) => {
      qc.setQueryData(["tenants", tenantId, "members"], (prev: typeof members.data) =>
        (prev ?? []).concat(created)
      );
      setEmail("");
      setFirstName("");
      setLastName("");
      notify(`Invitation sent to ${created.email}`, "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Invite failed", "error");
    },
  });

  const remove = useMutation({
    mutationFn: (userId: string) => membersApi.remove(tenantId, userId),
    onMutate: async (userId) => {
      const prev = qc.getQueryData(["tenants", tenantId, "members"]) as typeof members.data;
      qc.setQueryData(["tenants", tenantId, "members"], (rows: typeof members.data) =>
        (rows ?? []).filter((m) => m.userId !== userId)
      );
      return { prev };
    },
    onError: (e, _userId, ctx) => {
      if (ctx?.prev) qc.setQueryData(["tenants", tenantId, "members"], ctx.prev);
      notify(e instanceof Error ? e.message : "Remove failed", "error");
    },
    onSuccess: () => {
      notify("Member removed", "info");
    },
  });

  function sendInvite() {
    setErr("");
    if (!/^\S+@\S+\.\S+$/.test(email)) {
      setErr("Enter a valid email address.");
      return;
    }
    if (!firstName.trim() || !lastName.trim()) {
      setErr("First and last name are required.");
      return;
    }
    const dup = (members.data ?? []).some((m) => m.email.toLowerCase() === email.toLowerCase());
    if (dup) {
      setErr("This person is already a member.");
      return;
    }
    invite.mutate({ email, firstName: firstName.trim(), lastName: lastName.trim(), role });
  }

  const list = members.data ?? [];

  return (
    <div
      className={canManage ? "grid-2" : ""}
      style={canManage ? { gridTemplateColumns: "1.6fr 1fr" } : undefined}
    >
      <Card>
        <CardHead title="Members" />
        <BackendStatus isLoading={members.isLoading} error={members.error}>
          <table className="tbl">
            <thead>
              <tr>
                <th>Member</th>
                <th>Role</th>
                <th>Joined</th>
                {canManage && <th style={{ width: 32 }}></th>}
              </tr>
            </thead>
            <tbody>
              {list.length === 0 ? (
                <tr>
                  <td colSpan={canManage ? 4 : 3} style={{ padding: 24, textAlign: "center", color: "var(--fg-3)" }}>
                    {canManage ? "No members yet — invite someone →" : "No members yet."}
                  </td>
                </tr>
              ) : (
                list.map((m) => {
                  const isOwner = m.userId === ownerUserId;
                  return (
                    <tr key={m.userId}>
                      <td>
                        <div className="user-cell">
                          <Avatar name={m.name} />
                          <div>
                            <div className="user-cell-name">{m.name}</div>
                            <div className="user-cell-email">{m.email}</div>
                          </div>
                        </div>
                      </td>
                      <td>
                        <Chip variant={isOwner ? "info" : "muted"} dot={false}>
                          {isOwner ? `${m.role} · Owner` : m.role}
                        </Chip>
                      </td>
                      <td className="mono muted">
                        {m.status === "INVITED" ? "Invited" : formatGB(m.joinedAt)}
                      </td>
                      {canManage && (
                        <td>
                          <button
                            className="btn btn-ghost btn-icon btn-sm"
                            onClick={() => remove.mutate(m.userId)}
                            title="Remove"
                            disabled={isOwner}
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
      </Card>

      {canManage && (
      <Card>
        <CardHead title="Invite user" />
        <CardBody>
          <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            <div className="field">
              <label className="field-label">Email</label>
              <input
                className="input"
                type="email"
                placeholder="name@example.com"
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  setErr("");
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter") sendInvite();
                }}
                style={err ? { borderColor: "var(--bad)" } : undefined}
              />
              {err ? (
                <span className="field-hint" style={{ color: "var(--bad)" }}>
                  {err}
                </span>
              ) : (
                <span className="field-hint">Invitation link is valid for 3 days.</span>
              )}
            </div>
            <div className="grid-2" style={{ gap: 10 }}>
              <div className="field">
                <label className="field-label">First name</label>
                <input
                  className="input"
                  value={firstName}
                  onChange={(e) => setFirstName(e.target.value)}
                  placeholder="Marisol"
                />
              </div>
              <div className="field">
                <label className="field-label">Last name</label>
                <input
                  className="input"
                  value={lastName}
                  onChange={(e) => setLastName(e.target.value)}
                  placeholder="Vega"
                />
              </div>
            </div>
            <div className="field">
              <label className="field-label">Role</label>
              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: `repeat(${INVITE_ROLES.length}, 1fr)`,
                  gap: 4,
                  border: "1px solid var(--border)",
                  borderRadius: 6,
                  padding: 4,
                }}
              >
                {INVITE_ROLES.map((r) => (
                  <button
                    key={r}
                    type="button"
                    className={"btn btn-sm " + (r === role ? "btn-primary" : "btn-ghost")}
                    style={{ justifyContent: "center", width: "100%" }}
                    onClick={() => setRole(r)}
                  >
                    {r}
                  </button>
                ))}
              </div>
            </div>
            <button
              className="btn btn-primary"
              style={{ justifyContent: "center" }}
              onClick={sendInvite}
              disabled={invite.isPending}
            >
              <Icon d={Icons.mail} size={14} />
              {invite.isPending ? "Sending…" : "Send invitation"}
            </button>
          </div>
        </CardBody>
      </Card>
      )}
    </div>
  );
}
