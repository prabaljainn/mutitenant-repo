"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useMemo } from "react";

import { MetadataView } from "@/components/audit/MetadataView";
import { Card, CardBody, CardHead } from "@/components/ui/Card";
import { Chip } from "@/components/ui/Chip";
import { BackendStatus } from "@/components/ui/EmptyState";
import { auditApi } from "@/lib/api/audit";
import { operatorsApi } from "@/lib/api/operators";
import { formatGB, formatTime } from "@/lib/utils/date";

const MAX_ROWS = 10;
/** Pull a larger window so even chatty tenants get a few settings rows in. */
const FETCH_WINDOW = 50;

/** Coarse action → chip variant, kept in sync with the /audit page styles. */
function actionVariant(action: string): "good" | "warn" | "bad" | "info" | "muted" {
  if (action === "TENANT_SETTING_DELETED") return "bad";
  if (action === "TENANT_SETTING_TESTED") return "info";
  return "muted"; // TENANT_SETTING_UPDATED
}

function humanAction(action: string): string {
  return action.replace(/^TENANT_SETTING_/, "").toLowerCase();
}

/**
 * Compact "Recent changes" view for a tenant's settings. Reads from the
 * audit log (filtered to the {@code TENANT_SETTING_*} family) so we never
 * have to maintain a parallel history table. Answers "when did mqtt.host
 * change" without leaving the settings page.
 */
export function SettingsHistoryCard({ tenantId }: { tenantId: string }) {
  // Backend audit filter is one-of-three (action OR tenant OR actor); we
  // ask for the tenant slice and post-filter to the settings actions.
  const audit = useQuery({
    queryKey: ["audit", "settings-history", tenantId],
    queryFn: () => auditApi.list({ tenantId, size: FETCH_WINDOW }),
    retry: false,
  });
  // Operators are needed to resolve actorUserId → email/name in the table.
  // Cheap shared cache key with the /operators list page.
  const operators = useQuery({
    queryKey: ["operators", "for-audit"],
    queryFn: () => operatorsApi.list(),
    retry: false,
  });

  const operatorById = useMemo(
    () => new Map((operators.data ?? []).map((o) => [o.id, o])),
    [operators.data],
  );

  const settingsRows = useMemo(
    () =>
      (audit.data ?? [])
        .filter((r) => r.action.startsWith("TENANT_SETTING_"))
        .slice(0, MAX_ROWS),
    [audit.data],
  );

  return (
    <div style={{ marginTop: 16 }}>
      <Card>
        <CardHead
          title="Recent changes"
          sub="Settings edits, tests, and deletions for this tenant."
          right={
            <Link
              href={`/audit?tenantId=${encodeURIComponent(tenantId)}`}
              className="btn btn-ghost btn-sm"
            >
              View full audit →
            </Link>
          }
        />
        <BackendStatus isLoading={audit.isLoading} error={audit.error}>
          {settingsRows.length === 0 ? (
            <CardBody>
              <div className="muted" style={{ fontSize: 13 }}>
                No settings changes recorded yet.
              </div>
            </CardBody>
          ) : (
            <table className="tbl">
              <thead>
                <tr>
                  <th style={{ width: 140 }}>When</th>
                  <th style={{ width: 130 }}>Action</th>
                  <th>Actor</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {settingsRows.map((r) => {
                  const op = r.actorUserId ? operatorById.get(r.actorUserId) : null;
                  const hasMeta = Object.keys(r.metadata).length > 0;
                  return (
                    <tr key={r.id}>
                      <td className="mono muted">
                        <div>{formatGB(r.timestamp)}</div>
                        <div style={{ fontSize: 11 }}>{formatTime(r.timestamp)}</div>
                      </td>
                      <td>
                        <Chip variant={actionVariant(r.action)} dot={false}>
                          {humanAction(r.action)}
                        </Chip>
                      </td>
                      <td>
                        {op ? (
                          <span className="mono">{op.email}</span>
                        ) : (
                          <span className="mono muted">{r.actorUserId ?? "system"}</span>
                        )}
                      </td>
                      <td>
                        {hasMeta ? (
                          <details>
                            <summary
                              className="muted"
                              style={{ cursor: "pointer", fontSize: 12 }}
                            >
                              {Object.keys(r.metadata).length} field
                              {Object.keys(r.metadata).length === 1 ? "" : "s"}
                            </summary>
                            <div style={{ marginTop: 6 }}>
                              <MetadataView
                                metadata={r.metadata}
                                operatorById={operatorById}
                              />
                            </div>
                          </details>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </BackendStatus>
      </Card>
    </div>
  );
}
