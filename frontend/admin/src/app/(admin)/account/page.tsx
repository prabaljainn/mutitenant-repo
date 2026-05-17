"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Topbar } from "@/components/shell/Topbar";
import { Card, CardBody, CardHead } from "@/components/ui/Card";
import { Field } from "@/components/ui/Field";
import { BackendStatus } from "@/components/ui/EmptyState";
import { deriveSessionId, meApi } from "@/lib/api/me";
import { useAuth } from "@/lib/auth/AuthProvider";
import { useToast } from "@/lib/toast/ToastProvider";
import { formatGB, formatTime } from "@/lib/utils/date";

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
            <input
              className="input"
              type="password"
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
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </Field>
          <Field
            label="Confirm new password"
            error={mismatch ? "Doesn't match the new password." : undefined}
          >
            <input
              className="input"
              type="password"
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
  const [currentId, setCurrentId] = useState<string | null>(null);

  const sessions = useQuery({
    queryKey: ["me", "sessions"],
    queryFn: () => meApi.listSessions(),
  });

  // Compute the current session's id once at mount by hashing the refresh
  // token in sessionStorage. The raw token never leaves this component.
  useEffect(() => {
    const rt = typeof window === "undefined" ? null : sessionStorage.getItem("cloudgcs.refresh");
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

  function onRevoke(id: string) {
    if (id === currentId) {
      const ok = window.confirm(
        "This is the session you're using right now. Revoking it will sign you out of this tab. Continue?",
      );
      if (!ok) return;
    }
    revoke.mutate(id);
  }

  const rows = sessions.data ?? [];

  return (
    <Card>
      <CardHead
        title="Active sessions"
        sub="Each row is one device / tab where you're signed in."
      />
      <CardBody className="card-body-flush">
        <BackendStatus isLoading={sessions.isLoading} error={sessions.error}>
          <table className="tbl">
            <thead>
              <tr>
                <th>Session</th>
                <th>Started</th>
                <th>Expires</th>
                <th style={{ width: 100 }}></th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 ? (
                <tr>
                  <td
                    colSpan={4}
                    style={{ padding: 24, textAlign: "center", color: "var(--fg-3)" }}
                  >
                    No active sessions.
                  </td>
                </tr>
              ) : (
                rows.map((s) => {
                  const isCurrent = s.id === currentId;
                  return (
                    <tr key={s.id}>
                      <td>
                        <span className="mono">{s.id}</span>{" "}
                        {isCurrent && (
                          <span
                            className="chip info"
                            style={{ marginLeft: 6, fontSize: 11 }}
                          >
                            <span className="chip-dot" />
                            This device
                          </span>
                        )}
                      </td>
                      <td className="mono muted">
                        {formatGB(s.issuedAt)} {formatTime(s.issuedAt)}
                      </td>
                      <td className="mono muted">
                        {formatGB(s.expiresAt)} {formatTime(s.expiresAt)}
                      </td>
                      <td>
                        <button
                          className="btn btn-ghost btn-sm btn-danger"
                          disabled={revoke.isPending}
                          onClick={() => onRevoke(s.id)}
                        >
                          <Icon d={Icons.trash} size={12} /> Revoke
                        </button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
          <div
            className="muted"
            style={{ padding: "12px 16px", fontSize: 11, lineHeight: 1.5 }}
          >
            Sessions are kept in memory on the platform — a server restart signs
            everyone out. {" "}
            Refresh tokens are opaque and never shown here; the &ldquo;Session&rdquo;
            value is a one-way hash used only to identify the row.
          </div>
        </BackendStatus>
      </CardBody>
    </Card>
  );
}
