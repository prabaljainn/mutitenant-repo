"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import { AuthRadar } from "@/components/auth/AuthRadar";
import { forgotPassword } from "@/lib/api/auth";
import { useTheme } from "@/lib/theme/ThemeProvider";

export default function ForgotPasswordPage() {
  const { tweaks } = useTheme();
  const [email, setEmail] = useState("");
  const [err, setErr] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr("");
    if (!/^\S+@\S+\.\S+$/.test(email)) {
      setErr("Enter a valid email address.");
      return;
    }
    setSubmitting(true);
    try {
      await forgotPassword(email.trim());
      // Backend always 204s — never reveal whether the email exists.
      // Show the same generic confirmation either way.
      setDone(true);
    } catch (e: unknown) {
      // A network/500 should still get an error — only the
      // "address-doesn't-exist" branch is silent on the server.
      setErr(e instanceof Error ? e.message : "Could not send the reset email.");
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
            <SentNotice email={email} />
          ) : (
            <form className="auth-form" onSubmit={submit}>
              <h1>Forgot password</h1>
              <p className="sub">
                Enter your account email. If we recognise it, we&apos;ll send a link to choose a
                new password.
              </p>

              <div className="field">
                <label className="field-label">Email</label>
                <input
                  className="input"
                  type="email"
                  autoFocus
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    setErr("");
                  }}
                  disabled={submitting}
                />
              </div>

              {err && (
                <div className="field-hint" style={{ color: "var(--bad)", marginTop: 8 }}>
                  {err}
                </div>
              )}

              <button className="btn btn-primary submit" disabled={submitting}>
                {submitting ? "Sending…" : "Send reset link"}
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

function SentNotice({ email }: { email: string }) {
  return (
    <div className="auth-form">
      <h1>Check your inbox</h1>
      <p className="sub">
        If <span className="mono">{email}</span> belongs to an active account, a password-reset
        link is on its way. The link expires in a few hours.
      </p>
      <div className="alt" style={{ marginTop: 24 }}>
        <Link href="/login">Back to sign in</Link>
      </div>
    </div>
  );
}
