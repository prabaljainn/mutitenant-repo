"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Topbar } from "@/components/shell/Topbar";
import { Card, CardBody, CardHead } from "@/components/ui/Card";
import { Field } from "@/components/ui/Field";
import { BackendStatus } from "@/components/ui/EmptyState";
import { PasswordInput } from "@/components/ui/PasswordInput";
import { deriveSessionId, meApi } from "@/lib/api/me";
import type { Session } from "@/lib/api/types";
import { useAuth } from "@/lib/auth/AuthProvider";
import { useConfirm } from "@/lib/confirm/ConfirmProvider";
import { useToast } from "@/lib/toast/ToastProvider";
import { formatGB, formatRelative, formatTime } from "@/lib/utils/date";
import { describeUserAgent } from "@/lib/utils/userAgent";

export default function AccountPage() {
  return (
    <>
      <Topbar crumbs={[{ label: "Admin", href: "/overview" }, { label: "Account" }]} />
      <div className="page">
        <div className="page-head">
          <div>
            <div className="page-title">Account</div>
            <div className="page-sub">Manage your profile, password, and active sessions.</div>
          </div>
        </div>

        <div style={{ display: "grid", gap: 16 }}>
          <ProfileCard />
          <PasswordCard />
          <SessionsCard />
        </div>
      </div>
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile
// ─────────────────────────────────────────────────────────────────────────────

function ProfileCard() {
  const { claims } = useAuth();
  const qc = useQueryClient();
  const { notify } = useToast();

  // No /me/profile GET — seed from the JWT email and let the user fill the
  // names. After a successful PATCH we get the canonical values back.
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [seeded, setSeeded] = useState(false);

  const profile = useQuery({
    queryKey: ["me", "profile"],
    queryFn: () => meApi.getProfile(),
  });

  useEffect(() => {
    if (profile.data && !seeded) {
      setFirstName(profile.data.firstName ?? "");
      setLastName(profile.data.lastName ?? "");
      setSeeded(true);
    }
  }, [profile.data, seeded]);

  const save = useMutation({
    mutationFn: () => meApi.updateProfile({ firstName, lastName }),
    onSuccess: (p) => {
      qc.setQueryData(["me", "profile"], p);
      notify("Profile updated", "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Update failed", "error");
    },
  });

  const dirty = profile.data &&
    (firstName !== (profile.data.firstName ?? "") ||
      lastName !== (profile.data.lastName ?? ""));

  return (
    <Card>
      <CardHead title="Profile" sub="Change your display name." />
      <CardBody>
        <BackendStatus isLoading={profile.isLoading} error={profile.error}>
          <div
            style={{
              display: "grid",
              gap: 14,
              gridTemplateColumns: "minmax(0, 1fr) minmax(0, 1fr)",
              maxWidth: 560,
            }}
          >
            <div style={{ gridColumn: "1 / -1" }}>
              <Field label="Email">
                <input
                  className="input"
                  value={claims?.email ?? ""}
                  disabled
                  readOnly
                />
              </Field>
            </div>
            <Field label="First name">
              <input
                className="input"
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                maxLength={100}
              />
            </Field>
            <Field label="Last name">
              <input
                className="input"
                value={lastName}
                onChange={(e) => setLastName(e.target.value)}
                maxLength={100}
              />
            </Field>
          </div>
          <div style={{ marginTop: 16, display: "flex", gap: 8 }}>
            <button
              className="btn btn-primary"
              disabled={!dirty || save.isPending || !firstName.trim() || !lastName.trim()}
              onClick={() => save.mutate()}
            >
              {save.isPending ? "Saving…" : "Save changes"}
            </button>
          </div>
        </BackendStatus>
      </CardBody>
    </Card>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Password
// ─────────────────────────────────────────────────────────────────────────────

function PasswordCard() {
  const { persistSession } = useAuth();
  const { notify } = useToast();
  const [currentPassword, setCurrent] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");

  const change = useMutation({
    mutationFn: () => meApi.changePassword({ currentPassword, newPassword }),
    onSuccess: (tokens) => {
      // Keep this tab signed in with the freshly-issued pair. Other tabs
      // / devices are revoked server-side as a side-effect.
      persistSession(tokens.accessToken, tokens.refreshToken);
      setCurrent("");
      setNewPassword("");
      setConfirm("");
      notify("Password changed. Other sessions have been signed out.", "success");
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Password change failed", "error");
    },
  });

  const mismatch = confirm.length > 0 && confirm !== newPassword;
  const sameAsOld = newPassword.length > 0 && newPassword === currentPassword;
  const tooShort = newPassword.length > 0 && newPassword.length < 8;
  const canSubmit =
    currentPassword.length > 0 &&
    newPassword.length >= 8 &&
    confirm === newPassword &&
    newPassword !== currentPassword;

  return (
    <Card>
      <CardHead
        title="Password"
        sub="Changing your password signs out every other device."
      />
      <CardBody>
        <div style={{ display: "grid", gap: 14, maxWidth: 420 }}>
          <Field label="Current password">
            <PasswordInput
              autoComplete="current-password"
              value={currentPassword}
              onChange={(e) => setCurrent(e.target.value)}
            />
          </Field>
          <Field
            label="New password"
            hint={tooShort ? undefined : "Minimum 8 characters."}
            error={tooShort ? "Minimum 8 characters." : sameAsOld ? "Must differ from current." : undefined}
          >
            <PasswordInput
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </Field>
          <Field
            label="Confirm new password"
            error={mismatch ? "Doesn't match the new password." : undefined}
          >
            <PasswordInput
              autoComplete="new-password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
            />
          </Field>
        </div>
        <div style={{ marginTop: 16, display: "flex", gap: 8 }}>
          <button
            className="btn btn-primary"
            disabled={!canSubmit || change.isPending}
            onClick={() => change.mutate()}
          >
            {change.isPending ? "Saving…" : "Change password"}
          </button>
        </div>
      </CardBody>
    </Card>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Active sessions
// ─────────────────────────────────────────────────────────────────────────────

function SessionsCard() {
  const qc = useQueryClient();
  const { notify } = useToast();
  const confirm = useConfirm();
  const [currentId, setCurrentId] = useState<string | null>(null);

  const sessions = useQuery({
    queryKey: ["me", "sessions"],
    queryFn: () => meApi.listSessions(),
    // Refresh every minute so "last active 2 min ago" stays roughly true
    // without the user having to reload the page.
    refetchInterval: 60_000,
  });

  // Compute the current session's id once at mount by hashing the
  // refresh token in localStorage. The raw token never leaves this
  // component — only the derived 16-hex SHA-256 prefix is used.
  useEffect(() => {
    const rt = typeof window === "undefined" ? null : window.localStorage.getItem("cloudgcs.refresh");
    void deriveSessionId(rt).then(setCurrentId);
  }, []);

  const revoke = useMutation({
    mutationFn: (id: string) => meApi.revokeSession(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["me", "sessions"] });
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Revoke failed", "error");
    },
  });

  const revokeOthers = useMutation({
    mutationFn: () => meApi.revokeOtherSessions(currentId),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ["me", "sessions"] });
      const n = res?.count ?? 0;
      if (n === 0) {
        notify("No other sessions were signed in.", "info");
      } else if (n === 1) {
        notify("Signed out of 1 other device.", "success");
      } else {
        notify(`Signed out of ${n} other devices.`, "success");
      }
    },
    onError: (e: unknown) => {
      notify(e instanceof Error ? e.message : "Couldn't sign out other sessions.", "error");
    },
  });

  async function onRevoke(id: string) {
    if (id === currentId) {
      const ok = await confirm({
        title: "Sign out of this tab?",
        body: "This is the session you're using right now — revoking it will sign you out immediately.",
        confirmLabel: "Sign out",
        tone: "danger",
      });
      if (!ok) return;
    }
    revoke.mutate(id);
  }

  async function onRevokeOthers() {
    const others = (sessions.data ?? []).filter((s) => s.id !== currentId).length;
    if (others === 0) {
      notify("No other sessions to sign out.", "info");
      return;
    }
    const ok = await confirm({
      title: `Sign out of ${others} other ${others === 1 ? "device" : "devices"}?`,
      body: "They'll be signed out within the next 15 minutes. This device stays signed in.",
      confirmLabel: "Sign out everywhere else",
      tone: "danger",
    });
    if (!ok) return;
    revokeOthers.mutate();
  }

  const rows = sessions.data ?? [];
  const others = rows.filter((s) => s.id !== currentId).length;

  return (
    <Card>
      <CardHead
        title="Active sessions"
        sub="Each card is one device or tab where you're signed in."
        right={
          <button
            type="button"
            className="btn btn-ghost btn-sm btn-danger"
            disabled={others === 0 || revokeOthers.isPending}
            onClick={onRevokeOthers}
            title={others === 0 ? "No other sessions" : "Sign out everywhere else"}
          >
            <Icon d={Icons.switch} size={12} />{" "}
            {revokeOthers.isPending ? "Signing out…" : "Sign out everywhere else"}
          </button>
        }
      />
      <CardBody>
        <BackendStatus isLoading={sessions.isLoading} error={sessions.error}>
          {rows.length === 0 ? (
            <div style={{ padding: 24, textAlign: "center", color: "var(--fg-3)" }}>
              No active sessions.
            </div>
          ) : (
            <ul className="session-list">
              {rows.map((s) => (
                <SessionRow
                  key={s.id}
                  session={s}
                  isCurrent={s.id === currentId}
                  onRevoke={() => onRevoke(s.id)}
                  revoking={revoke.isPending && revoke.variables === s.id}
                />
              ))}
            </ul>
          )}
          <div className="session-footnote">
            Sessions are stored in memory on the platform — a server restart signs
            everyone out. The session id is a one-way hash; the underlying refresh
            token is never exposed.
          </div>
        </BackendStatus>
      </CardBody>
    </Card>
  );
}

function SessionRow({
  session,
  isCurrent,
  onRevoke,
  revoking,
}: {
  session: Session;
  isCurrent: boolean;
  onRevoke: () => void;
  revoking: boolean;
}) {
  const device = describeUserAgent(session.userAgent);
  return (
    <li className={"session-row" + (isCurrent ? " session-row--current" : "")}>
      <div className="session-row-icon" aria-hidden>
        <Icon d={device.mobile ? Icons.signal : Icons.console} size={18} />
      </div>
      <div className="session-row-main">
        <div className="session-row-title">
          <span className="session-row-device">{device.label}</span>
          {isCurrent && (
            <span className="chip info" style={{ fontSize: 11 }}>
              <span className="chip-dot" />
              This device
            </span>
          )}
        </div>
        <div className="session-row-meta">
          <span title={`${formatGB(session.issuedAt)} ${formatTime(session.issuedAt)}`}>
            Last active {formatRelative(session.issuedAt)}
          </span>
          <span aria-hidden>·</span>
          <span title={`First seen ${formatGB(session.firstSeenAt)}`}>
            Signed in {formatRelative(session.firstSeenAt)}
          </span>
          {session.ip && (
            <>
              <span aria-hidden>·</span>
              <span className="mono">{session.ip}</span>
            </>
          )}
        </div>
      </div>
      <button
        type="button"
        className="btn btn-ghost btn-sm btn-danger"
        disabled={revoking}
        onClick={onRevoke}
      >
        <Icon d={Icons.trash} size={12} /> {revoking ? "Revoking…" : "Revoke"}
      </button>
    </li>
  );
}
