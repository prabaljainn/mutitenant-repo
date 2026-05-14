"use client";

// Wires the API client's static auth accessor to React state once at mount.
// Keeps the fetch wrapper free of React imports while still giving it live
// access to the latest token after every refresh.

import { useEffect, type ReactNode } from "react";

import { bindAuth } from "@/lib/api/client";
import { useAuth } from "./AuthProvider";

export function AuthBridge({ children }: { children: ReactNode }) {
  const { accessToken, refresh, signOut } = useAuth();
  useEffect(() => {
    bindAuth({
      getAccessToken: () => accessToken,
      refresh,
      signOut,
    });
  }, [accessToken, refresh, signOut]);
  return <>{children}</>;
}
