"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useMemo, useState, type FormEvent } from "react";

// useSearchParams() bails out of static prerender. Without this, `next
// build` errors with "should be wrapped in a suspense boundary". The
// page has no useful prerenderable shell anyway — the token from the
// URL is the only thing that matters.
export const dynamic = "force-dynamic";

import { AuthRadar } from "@/components/auth/AuthRadar";
import { acceptInvite } from "@/lib/api/auth";
import { useAuth } from "@/lib/auth/AuthProvider";
import { decodeJwt } from "@/lib/auth/jwt";
import { useTheme } from "@/lib/theme/ThemeProvider";

const MIN_PASSWORD_LENGTH = 8;

type ActivationResult =
  | { kind: "OPERATOR" }
  | { kind: "TENANT_USER" }
  | { kind: "UNKNOWN" };

export default function AcceptInvitePage() {
  const router = useRouter();
  const { tweaks } = useTheme();
  const { persistSession } = useAuth();
  const params = useSearchParams();
  const token = useMemo(() => params.get("token") ?? "", [params]);

  const [pw, setPw] = useState("");
  const [confirm, setConfirm] = useState("");
  const [err, setErr] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [activated, setActivated] = useState<ActivationResult | null>(null);

  const tokenMissing = token.trim().length === 0;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr("");

    if (tokenMissing) {
      setErr("This invitation link is missing its token. Ask the inviter to resend the invite.");
      return;
    }
    if (pw.length < MIN_PASSWORD_LENGTH) {
      setErr(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`);
      return;
    }
    if (pw !== confirm) {
      setErr("Passwords don't match.");
      return;
    }

    setSubmitting(true);
    try {
      const tokens = await acceptInvite(token, pw);
      const claims = decodeJwt(tokens.accessToken);

      if (claims?.kind === "OPERATOR") {
        // Operator: log them straight in and route to the admin home.
        persistSession(tokens.accessToken, tokens.refreshToken);
        router.replace("/overview");
        return;
      }

      // Tenant user: the admin console isn't their app. Show a confirmation
      // and leave them to sign in wherever their tenant frontend lives.
      // (Today there isn't one — the message is placeholder until it ships.)
      setActivated({ kind: claims?.kind ?? "UNKNOWN" });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "Could not activate your account.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="app-shell" data-theme={tweaks.theme} data-density="balanced">
      <div className="auth-shell">
        <div className="auth-form-side">
          <div className="auth-brand">
            <div className="sb-logo">S</div>
            <div className="auth-brand-text">CloudGCS Admin</div>
          </div>

          {activated ? (
            <ActivatedNotice kind={activated.kind} />
          ) : (
            <form className="auth-form" onSubmit={submit}>
              <h1>Set your password</h1>
              <p className="sub">
                Welcome to Orochiverse. Choose a password to finish activating your account.
              </p>

              {tokenMissing && (
                <div className="field-hint" style={{ color: "var(--bad)", marginBottom: 12 }}>
                  This link is missing an invitation token. It may have been truncated by your email
                  client — try copying the URL directly from the email.
                </div>
              )}

              <div className="field">
                <label className="field-label">New password</label>
                <input
                  className="input"
                  type="password"
                  autoFocus
                  value={pw}
                  onChange={(e) => {
                    setPw(e.target.value);
                    setErr("");
                  }}
                  disabled={tokenMissing || submitting}
                />
                <span className="field-hint">At least {MIN_PASSWORD_LENGTH} characters.</span>
              </div>

              <div className="field" style={{ marginTop: 14 }}>
                <label className="field-label">Confirm password</label>
                <input
                  className="input"
                  type="password"
                  value={confirm}
                  onChange={(e) => {
                    setConfirm(e.target.value);
                    setErr("");
                  }}
                  disabled={tokenMissing || submitting}
                />
              </div>

              {err && (
                <div className="field-hint" style={{ color: "var(--bad)", marginTop: 8 }}>
                  {err}
                </div>
              )}

              <button className="btn btn-primary submit" disabled={tokenMissing || submitting}>
                {submitting ? "Activating…" : "Activate account"}
              </button>

              <div className="alt">
                Already activated?{" "}
                <Link href="/login">Sign in</Link>
              </div>
            </form>
          )}

          <div className="auth-foot">CloudGCS · admin console</div>
        </div>
        <AuthRadar />
      </div>
    </div>
  );
}

function ActivatedNotice({ kind }: { kind: "OPERATOR" | "TENANT_USER" | "UNKNOWN" }) {
  // Tenant users land here after activation — the admin console isn't their
  // home. Operators never reach this branch (they're redirected by the page).
  return (
    <div className="auth-form">
      <h1>Account activated</h1>
      <p className="sub">
        Your password is set and you can now sign in.
      </p>
      <div
        className="field-hint"
        style={{ marginTop: 12, padding: 12, border: "1px solid var(--border)", borderRadius: 6 }}
      >
        {kind === "TENANT_USER" ? (
          <>
            You belong to a tenant workspace. Once the tenant sign-in page is available,
            you&apos;ll be able to log in there with your new password. Ask your tenant
            admin if you need a direct link.
          </>
        ) : (
          <>
            Your account is active. Use your new password to sign in from your normal
            entry point.
          </>
        )}
      </div>
      <Link href="/login" className="btn submit" style={{ justifyContent: "center" }}>
        Continue
      </Link>
    </div>
  );
}
