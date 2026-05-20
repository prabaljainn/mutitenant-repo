"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState, type FormEvent } from "react";

import { AuthRadar } from "@/components/auth/AuthRadar";
import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Alert } from "@/components/ui/Alert";
import { PasswordInput } from "@/components/ui/PasswordInput";
import { PasswordStrength } from "@/components/ui/PasswordStrength";
import { resetPassword } from "@/lib/api/auth";
import { useTheme } from "@/lib/theme/ThemeProvider";

const MIN_PASSWORD_LENGTH = 8;

// Same Suspense wrapping pattern as /accept-invite — useSearchParams()
// requires a boundary or the static-prerender pass errors.
export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetPasswordImpl />
    </Suspense>
  );
}

function ResetPasswordImpl() {
  const router = useRouter();
  const { tweaks } = useTheme();
  const params = useSearchParams();
  const token = useMemo(() => params.get("token") ?? "", [params]);

  const [pw, setPw] = useState("");
  const [confirm, setConfirm] = useState("");
  const [err, setErr] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const tokenMissing = token.trim().length === 0;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr("");

    if (tokenMissing) {
      setErr("This reset link is missing its token. Request a new password reset email.");
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
      await resetPassword(token, pw);
      // Backend returns 204 and does NOT auto-log the user in (per the
      // /api/auth/reset-password contract). Send them to /login to sign in
      // with the new password. The query param lets the login page show a
      // success notice without us threading state another way.
      setDone(true);
      setTimeout(() => router.replace("/login?reset=ok"), 1500);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "Could not reset your password.");
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

          {done ? (
            <ResetDoneNotice />
          ) : (
            <form className="auth-form" onSubmit={submit}>
              <h1>Reset your password</h1>
              <p className="sub">
                Choose a new password for your account. You&apos;ll be redirected to sign in once
                it&apos;s saved.
              </p>

              {tokenMissing && (
                <div style={{ marginBottom: 16 }}>
                  <Alert tone="warn">
                    This link is missing a reset token. It may have been truncated by your email
                    client — try copying the URL directly from the email.
                  </Alert>
                </div>
              )}

              {err && (
                <div style={{ marginBottom: 16 }}>
                  <Alert tone="error">{err}</Alert>
                </div>
              )}

              <div className="field">
                <label className="field-label">New password</label>
                <PasswordInput
                  autoFocus
                  autoComplete="new-password"
                  value={pw}
                  onChange={(e) => {
                    setPw(e.target.value);
                    setErr("");
                  }}
                  disabled={tokenMissing || submitting}
                />
                <PasswordStrength
                  value={pw}
                  confirm={confirm}
                  minLength={MIN_PASSWORD_LENGTH}
                />
              </div>

              <div className="field" style={{ marginTop: 14 }}>
                <label className="field-label">Confirm password</label>
                <PasswordInput
                  autoComplete="new-password"
                  value={confirm}
                  onChange={(e) => {
                    setConfirm(e.target.value);
                    setErr("");
                  }}
                  disabled={tokenMissing || submitting}
                />
              </div>

              <button
                className="btn btn-primary submit"
                disabled={tokenMissing || submitting}
                aria-busy={submitting || undefined}
              >
                {submitting ? "Saving…" : "Save new password"}
              </button>

              <div className="alt">
                Remembered your password? <Link href="/login">Sign in</Link>
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

function ResetDoneNotice() {
  return (
    <div className="auth-form">
      <div className="auth-success-mark" aria-hidden>
        <Icon d={Icons.check} size={22} />
      </div>
      <h1>Password updated</h1>
      <p className="sub">Redirecting you to sign in…</p>
    </div>
  );
}
