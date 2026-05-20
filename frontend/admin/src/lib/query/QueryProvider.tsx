"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

export function QueryProvider({ children }: { children: ReactNode }) {
  // One client per mounted provider — keyed by the React tree, not module-global,
  // so React strict-mode double-render in dev doesn't share a stale client.
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            gcTime: 5 * 60_000,
            retry: (failureCount, err) => {
              // Don't retry on NotImplemented / Unauthorized — the user has to act.
              const status = (err as { status?: number } | undefined)?.status;
              if (status === 401 || status === 404 || status === 501) return false;
              return failureCount < 2;
            },
            refetchOnWindowFocus: false,
          },
          mutations: { retry: false },
        },
      })
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
