"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { AdminShell } from "@/components/shell/AdminShell";
import { useAuth } from "@/lib/auth/AuthProvider";

export default function AdminLayout({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  // Client-side gate: the middleware can't reach sessionStorage (where
  // the access token lives) so this is the actual auth check. Wait for
  // "hydrating" to settle — without that, a full-page refresh on any
  // authenticated route would briefly read status="anonymous" (before
  // the rehydration effect runs), bounce to /login, then /login's own
  // effect would bounce to /overview, losing the original path.
  //
  // When we DO bounce an anonymous user, stash the requested path as
  // ?next=… so the post-login redirect can land them back where they
  // were instead of always dumping them on /overview.
  useEffect(() => {
    if (status !== "anonymous") return;
    const next = pathname && pathname !== "/" ? pathname : null;
    const target = next ? `/login?next=${encodeURIComponent(next)}` : "/login";
    router.replace(target);
  }, [status, pathname, router]);

  if (status !== "authenticated") return null;
  return <AdminShell>{children}</AdminShell>;
}
