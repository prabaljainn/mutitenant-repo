"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Topbar } from "@/components/shell/Topbar";
import { Avatar } from "@/components/ui/Avatar";
import { Chip } from "@/components/ui/Chip";
import { BackendStatus } from "@/components/ui/EmptyState";
import { usersSearchApi } from "@/lib/api/usersSearch";
import type { UserSearchResult } from "@/lib/api/types";
import { formatGB } from "@/lib/utils/date";

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 300;

function statusChip(status: UserSearchResult["status"]) {
  switch (status) {
    case "ACTIVE":
      return <Chip variant="good">Active</Chip>;
    case "INVITED":
      return <Chip variant="warn">Invited</Chip>;
    case "SUSPENDED":
      return <Chip variant="bad">Suspended</Chip>;
    case "DELETED":
      return <Chip variant="muted">Deleted</Chip>;
  }
}

function kindChip(kind: UserSearchResult["kind"]) {
  return kind === "OPERATOR" ? (
    <Chip variant="info" dot={false}>Operator</Chip>
  ) : (
    <Chip variant="muted" dot={false}>Tenant user</Chip>
  );
}

export default function UserSearchPage() {
  const router = useRouter();
  const [input, setInput] = useState("");
  const [debounced, setDebounced] = useState("");

  // Debounce the input so we don't fire a request per keystroke.
  useEffect(() => {
    const t = setTimeout(() => setDebounced(input.trim()), DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [input]);

  const enabled = debounced.length >= MIN_QUERY_LENGTH;
  const results = useQuery({
    queryKey: ["users", "search", debounced],
    queryFn: () => usersSearchApi.search(debounced),
    enabled,
    // Don't keep stale results around when the query changes — feels weird
    // to see old rows blink while new ones land.
    placeholderData: undefined,
  });

  const rows = results.data ?? [];

  function rowHref(r: UserSearchResult): string {
    if (r.kind === "OPERATOR") return `/operators/${r.userId}`;
    // Tenant user → land on the tenant's Members tab. The members table
    // there shows the same row in context (with its tenant siblings).
    return `/tenants/${r.tenantId}?tab=members`;
  }

  return (
    <>
      <Topbar crumbs={[{ label: "Admin", href: "/overview" }, { label: "User search" }]} />
      <div className="page">
        <div className="page-head">
          <div>
            <div className="page-title">User search</div>
            <div className="page-sub">
              Find any operator or tenant user by email or name.
            </div>
          </div>
        </div>

        <div className="tbl-wrap">
          <div className="tbl-toolbar">
            <div
              className="tb-search"
              style={{ marginLeft: 0, width: 380, background: "var(--bg)" }}
            >
              <Icon d={Icons.search} size={14} />
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="Search by email, first name, or last name…"
                autoFocus
                style={{
                  flex: 1,
                  background: "transparent",
                  border: 0,
                  outline: 0,
                  color: "inherit",
                  fontFamily: "inherit",
                  fontSize: 12,
                }}
              />
            </div>
            <div className="grow" />
            <div className="muted" style={{ fontSize: 12 }}>
              {enabled && results.data
                ? `${rows.length} match${rows.length === 1 ? "" : "es"}`
                : null}
            </div>
          </div>

          {!enabled ? (
            <div
              className="muted"
              style={{ padding: 28, textAlign: "center", fontSize: 13 }}
            >
              Type at least {MIN_QUERY_LENGTH} characters to search.
            </div>
          ) : (
            <BackendStatus isLoading={results.isLoading} error={results.error}>
              <table className="tbl">
                <thead>
                  <tr>
                    <th>User</th>
                    <th>Kind</th>
                    <th>Tenant</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th>Last login</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.length === 0 ? (
                    <tr>
                      <td
                        colSpan={6}
                        style={{
                          padding: 28,
                          textAlign: "center",
                          color: "var(--fg-3)",
                        }}
                      >
                        No users match &ldquo;{debounced}&rdquo;.
                      </td>
                    </tr>
                  ) : (
                    rows.map((r) => (
                      <tr
                        key={r.userId}
                        style={{ cursor: "pointer" }}
                        onClick={() => router.push(rowHref(r))}
                      >
                        <td>
                          <div className="user-cell">
                            <Avatar name={r.name} />
                            <div>
                              <div className="user-cell-name">{r.name}</div>
                              <div className="user-cell-email">{r.email}</div>
                            </div>
                          </div>
                        </td>
                        <td>{kindChip(r.kind)}</td>
                        <td className="mono muted">{r.tenantId ?? "—"}</td>
                        <td className="muted">{r.role ?? "—"}</td>
                        <td>{statusChip(r.status)}</td>
                        <td className="mono muted">
                          {r.lastLoginAt ? formatGB(r.lastLoginAt) : "—"}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </BackendStatus>
          )}
        </div>
      </div>
    </>
  );
}
