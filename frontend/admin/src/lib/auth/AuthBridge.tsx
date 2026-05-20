"use client";

// Wires the API client's static auth accessor to React state once at mount.
// Keeps the fetch wrapper free of React imports while still giving it live
// access to the latest token after every refresh.

import { useEffect, type ReactNode } from "react";

import { bindAuth, bindUnreachable } from "@/lib/api/client";
import { useToast } from "@/lib/toast/ToastProvider";
import { useAuth } from "./AuthProvider";

export function AuthBridge({ children }: { children: ReactNode }) {
  const { accessToken, refresh, signOut } = useAuth();
  const { notify } = useToast();
  useEffect(() => {
    bindAuth({
      getAccessToken: () => accessToken,
      refresh,
      signOut,
    });
  }, [accessToken, refresh, signOut]);
  // Single session-wide toast when fetch itself fails (offline, DNS, dev
  // server stopped). Internally rate-limited in api/client.ts so a burst
  // of failing parallel queries doesn't spam.
  useEffect(() => {
    bindUnreachable(() => {
      notify(
        "Can't reach the platform — check your connection or try again shortly.",
        "error",
      );
    });
  }, [notify]);
  return <>{children}</>;
}
