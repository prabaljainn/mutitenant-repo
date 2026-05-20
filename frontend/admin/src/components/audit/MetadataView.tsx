"use client";

import type { Operator, Tenant } from "@/lib/api/types";

/**
 * Pretty-prints an audit row's metadata as a compact key/value list.
 *
 * Known id keys are resolved to human names when the lookup map has
 * them; everything else falls back to a string render. Nested objects
 * collapse to compact JSON so the row height stays reasonable.
 *
 * The component is deliberately dumb — no fetching. Pass the lookup
 * maps the page already has loaded.
 */
export function MetadataView({
  metadata,
  operatorById,
  tenantById,
}: {
  metadata: Record<string, unknown>;
  operatorById?: Map<string, Operator>;
  tenantById?: Map<string, Tenant>;
}) {
  const entries = Object.entries(metadata);
  if (entries.length === 0) return <span className="muted">—</span>;

  return (
    <dl
      style={{
        display: "grid",
        gridTemplateColumns: "auto 1fr",
        gap: "2px 10px",
        margin: 0,
        fontSize: 12,
        maxWidth: 420,
      }}
    >
      {entries.map(([key, value]) => (
        <Row
          key={key}
          fieldKey={key}
          value={value}
          operatorById={operatorById}
          tenantById={tenantById}
        />
      ))}
    </dl>
  );
}

function Row({
  fieldKey,
  value,
  operatorById,
  tenantById,
}: {
  fieldKey: string;
  value: unknown;
  operatorById?: Map<string, Operator>;
  tenantById?: Map<string, Tenant>;
}) {
  return (
    <>
      <dt className="muted" style={{ textTransform: "lowercase" }}>
        {humanKey(fieldKey)}
      </dt>
      <dd style={{ margin: 0 }}>
        {renderValue(fieldKey, value, operatorById, tenantById)}
      </dd>
    </>
  );
}

function humanKey(key: string): string {
  // Split camelCase / snake_case into a friendlier label.
  return key
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/_/g, " ")
    .toLowerCase();
}

function renderValue(
  key: string,
  value: unknown,
  operatorById?: Map<string, Operator>,
  tenantById?: Map<string, Tenant>,
) {
  if (value === null || value === undefined) {
    return <span className="muted">—</span>;
  }

  // Resolve known id keys to names when we have a map.
  if (typeof value === "string") {
    // Operator-side ids: operatorId, actorUserId-ish keys that are
    // operators in practice.
    if ((key === "operatorId" || key === "actorUserId") && operatorById?.has(value)) {
      return <NameTag name={operatorById.get(value)!.name} id={value} />;
    }
    // Tenant ids — these can also appear under from/to on ownership
    // transfer when the value happens to be a tuser id; harmless.
    if (key === "tenantId" && tenantById?.has(value)) {
      return <NameTag name={tenantById.get(value)!.name} id={value} />;
    }
    // ISO-ish timestamp string → format en-GB. Heuristic: matches "T".
    if (looksLikeIso(value)) {
      const d = new Date(value);
      if (!Number.isNaN(d.getTime())) {
        return (
          <span className="mono" title={value}>
            {d.toLocaleString("en-GB")}
          </span>
        );
      }
    }
    // Plain string fallback — most metadata keys land here.
    return <span className="mono">{value}</span>;
  }

  if (typeof value === "number" || typeof value === "boolean") {
    return <span className="mono">{String(value)}</span>;
  }

  // Nested object/array — compact JSON. Most common is "changes": {…}
  // on tenant updates.
  return (
    <code className="mono" style={{ wordBreak: "break-word", fontSize: 11 }}>
      {JSON.stringify(value)}
    </code>
  );
}

function NameTag({ name, id }: { name: string; id: string }) {
  return (
    <span>
      <span style={{ fontWeight: 500 }}>{name}</span>{" "}
      <span className="mono muted" style={{ fontSize: 11 }}>
        ({id})
      </span>
    </span>
  );
}

function looksLikeIso(s: string): boolean {
  // 2026-05-16T14:32:07Z and variants. Avoid picking up plain UUIDs.
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s);
}
