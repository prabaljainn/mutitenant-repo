"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import { AuthRadar } from "@/components/auth/AuthRadar";
import { useAuth } from "@/lib/auth/AuthProvider";
import { useTheme } from "@/lib/theme/ThemeProvider";

export default function LoginPage() {
  const router = useRouter();
  const { tweaks } = useTheme();
  const { signIn, status } = useAuth();

  // Default-fill only outside production to keep manual testing painless without
  // accidentally pre-filling for real super-admins.
  const isProd = process.env.NODE_ENV === "production";
  const [email, setEmail] = useState(isProd ? "" : "admin@orochiverse.local");
  const [pw, setPw] = useState(isProd ? "" : "");
  const [err, setErr] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (status === "authenticated") router.replace("/overview");
  }, [status, router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!/^\S+@\S+\.\S+$/.test(email)) {
      setErr("Enter a valid email.");
      return;
    }
    if (pw.length < 4) {
      setErr("Password is required.");
      return;
    }
    setErr("");
    setSubmitting(true);
    try {
      await signIn(email, pw);
      router.replace("/overview");
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "Sign-in failed.");
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
          <form className="auth-form" onSubmit={submit}>
            <h1>Sign in</h1>
            <p className="sub">Super-admin access to the CloudGCS multi-tenant console.</p>
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
              />
            </div>
            <div className="field" style={{ marginTop: 14 }}>
              <div className="field-row">
                <label className="field-label">Password</label>
                <a
                  href="#"
                  style={{ fontSize: 12, color: "var(--accent)", textDecoration: "none" }}
                  onClick={(e) => e.preventDefault()}
                >
                  Forgot?
                </a>
              </div>
              <input
                className="input"
                type="password"
                value={pw}
                onChange={(e) => {
                  setPw(e.target.value);
                  setErr("");
                }}
              />
            </div>
            {err && (
              <div className="field-hint" style={{ color: "var(--bad)", marginTop: 8 }}>
                {err}
              </div>
            )}
            <button className="btn btn-primary submit" disabled={submitting}>
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
