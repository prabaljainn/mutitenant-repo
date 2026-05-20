"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { AuthRadar } from "@/components/auth/AuthRadar";
import { Alert } from "@/components/ui/Alert";
import { PasswordInput } from "@/components/ui/PasswordInput";
import { useAuth } from "@/lib/auth/AuthProvider";
import { useTheme } from "@/lib/theme/ThemeProvider";

const EMAIL_RE = /^\S+@\S+\.\S+$/;

export default function LoginPage() {
  const router = useRouter();
  const { tweaks } = useTheme();
  const { signIn, status } = useAuth();

  // Default-fill only outside production to keep manual testing painless without
  // accidentally pre-filling for real super-admins.
  const isProd = process.env.NODE_ENV === "production";
  const [email, setEmail] = useState(isProd ? "" : "admin@orochiverse.local");
  const [pw, setPw] = useState("");
  const [emailTouched, setEmailTouched] = useState(false);
  const [err, setErr] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const errorRef = useRef<HTMLDivElement | null>(null);

  const emailId = useId();
  const passwordId = useId();

  const emailInvalid = emailTouched && email.length > 0 && !EMAIL_RE.test(email);

  useEffect(() => {
    if (status === "authenticated") router.replace("/overview");
  }, [status, router]);

  // Move focus to the error banner so screen-reader users hear it and
  // sighted users see it scroll into view on small viewports.
  useEffect(() => {
    if (err && errorRef.current) {
      errorRef.current.focus();
    }
  }, [err]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setEmailTouched(true);
    if (!EMAIL_RE.test(email)) {
      setErr("Please enter a valid email address.");
      return;
    }
    if (pw.length === 0) {
      setErr("Please enter your password.");
      return;
    }
    setErr("");
    setSubmitting(true);
    try {
      await signIn(email.trim(), pw);
      router.replace("/overview");
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "Sign-in failed. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="app-shell" data-theme={tweaks.theme} data-density="balanced">
      <div className="auth-shell">
        <div className="auth-form-side">
          <div className="auth-brand">
            {/* TODO(brand): swap the "S" placeholder for the real
                CloudGCS / Orochiverse wordmark. Tracked alongside the
                sidebar version (components/shell/Sidebar.tsx). */}
            <div className="sb-logo">S</div>
            <div className="auth-brand-text">CloudGCS Admin</div>
          </div>
          <form className="auth-form" onSubmit={submit} noValidate>
            <h1>Sign in</h1>
            <p className="sub">Super-admin access to the CloudGCS multi-tenant console.</p>

            {err && (
              <div ref={errorRef} tabIndex={-1} style={{ outline: "none", marginBottom: 18 }}>
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
                autoComplete="username"
                autoFocus
                spellCheck={false}
                autoCapitalize="off"
                aria-invalid={emailInvalid || undefined}
                aria-describedby={emailInvalid ? `${emailId}-err` : undefined}
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  setErr("");
                }}
                onBlur={() => setEmailTouched(true)}
              />
              {emailInvalid && (
                <span id={`${emailId}-err`} className="field-hint" style={{ color: "var(--bad)" }}>
                  That doesn&apos;t look like a valid email address.
                </span>
              )}
            </div>

            <div className="field" style={{ marginTop: 14 }}>
              <div className="field-row">
                <label className="field-label" htmlFor={passwordId}>
                  Password
                </label>
                <Link
                  href="/forgot-password"
                  style={{ fontSize: 12, color: "var(--accent)", textDecoration: "none" }}
                >
                  Forgot?
                </Link>
              </div>
              <PasswordInput
                id={passwordId}
                name="password"
                autoComplete="current-password"
                value={pw}
                onChange={(e) => {
                  setPw(e.target.value);
                  setErr("");
                }}
              />
            </div>

            <button
              className="btn btn-primary submit"
              disabled={submitting}
              aria-busy={submitting || undefined}
            >
              {submitting ? "Signing in…" : "Continue"}
            </button>
            <div className="alt">
              Need access?{" "}
              <a href="#" onClick={(e) => e.preventDefault()}>
                Contact your admin
              </a>
            </div>
          </form>
          <div className="auth-foot">CloudGCS · admin console</div>
        </div>
        <AuthRadar />
      </div>
    </div>
  );
}
