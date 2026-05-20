"use client";

import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { AdminShell } from "@/components/shell/AdminShell";
import { useAuth } from "@/lib/auth/AuthProvider";

export default function AdminLayout({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();

  // Client-side gate: the middleware can't reach sessionStorage (where the access
  // token lives) so this is the actual auth check. The middleware just handles
  // the unauthenticated /admin paths that the user hits with no JS yet loaded.
  useEffect(() => {
    if (status === "anonymous") router.replace("/login");
  }, [status, router]);

  if (status !== "authenticated") return null;
  return <AdminShell>{children}</AdminShell>;
}
