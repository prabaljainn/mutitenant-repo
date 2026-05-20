"use client";

import Link from "next/link";
import { useId, useState, type FormEvent } from "react";

import { AuthRadar } from "@/components/auth/AuthRadar";
import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Alert } from "@/components/ui/Alert";
import { forgotPassword } from "@/lib/api/auth";
import { useTheme } from "@/lib/theme/ThemeProvider";

const EMAIL_RE = /^\S+@\S+\.\S+$/;

export default function ForgotPasswordPage() {
  const { tweaks } = useTheme();
  const [email, setEmail] = useState("");
  const [touched, setTouched] = useState(false);
  const [err, setErr] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const emailId = useId();

  const emailInvalid = touched && email.length > 0 && !EMAIL_RE.test(email);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTouched(true);
    setErr("");
    if (!EMAIL_RE.test(email)) {
      setErr("Please enter a valid email address.");
      return;
    }
    setSubmitting(true);
    try {
      await forgotPassword(email.trim());
      // Backend always 204s — never reveal whether the email exists.
      // Show the same generic confirmation either way.
      setDone(true);
    } catch (e: unknown) {
      // Network / 500 still surfaces — only the "address-doesn't-exist"
      // branch is silent on the server, by design.
      setErr(e instanceof Error ? e.message : "Couldn't send the reset email. Please try again.");
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
            <form className="auth-form" onSubmit={submit} noValidate>
              <Link href="/login" className="auth-back-link">
                <Icon d={Icons.arrowL} size={14} />
                <span>Back to sign in</span>
              </Link>

              <h1>Forgot password</h1>
              <p className="sub">
                Enter your account email. If we recognise it, we&apos;ll send a link to choose a
                new password.
              </p>

              {err && (
                <div style={{ marginBottom: 18 }}>
                  <Alert tone="error">{err}</Alert>
                </div>
              )}

              <div className="field">
                <label className="field-label" htmlFor={emailId}>
                  Email
                </label>
                <input
                  id={emailId}
                  name="email"
                  className="input"
                  type="email"
                  autoFocus
                  autoComplete="username"
                  spellCheck={false}
                  autoCapitalize="off"
                  aria-invalid={emailInvalid || undefined}
                  aria-describedby={emailInvalid ? `${emailId}-err` : undefined}
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    setErr("");
                  }}
                  onBlur={() => setTouched(true)}
                  disabled={submitting}
                />
                {emailInvalid && (
                  <span
                    id={`${emailId}-err`}
                    className="field-hint"
                    style={{ color: "var(--bad)" }}
                  >
                    That doesn&apos;t look like a valid email address.
                  </span>
                )}
              </div>

              <button
                className="btn btn-primary submit"
                disabled={submitting}
                aria-busy={submitting || undefined}
              >
                {submitting ? "Sending…" : "Send reset link"}
              </button>

              <Link href="/login" className="btn submit auth-secondary">
                Back to sign in
              </Link>
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
      <div className="auth-success-mark" aria-hidden>
        <Icon d={Icons.mail} size={22} />
      </div>
      <h1>Check your inbox</h1>
      <p className="sub">
        If <span className="mono">{email}</span> belongs to an active account, a password-reset
        link is on its way. The link expires in a few hours.
      </p>
      <Link href="/login" className="btn btn-primary submit">
        Back to sign in
      </Link>
    </div>
  );
}
