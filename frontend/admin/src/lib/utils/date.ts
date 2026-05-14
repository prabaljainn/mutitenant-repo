// All dates from the backend are ISO strings. The UI shows them in en-GB
// short style ("14 Mar 2024") to match the prototype.

const formatter = new Intl.DateTimeFormat("en-GB", {
  day: "2-digit",
  month: "short",
  year: "numeric",
});

export function formatGB(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso; // surface garbage rather than hiding it
  return formatter.format(d);
}

const relativeFormatter = new Intl.RelativeTimeFormat("en-GB", { numeric: "auto" });

/** "14 min ago" / "2 h ago" / "yesterday" style relative timestamp. */
export function formatRelative(iso: string): string {
  const target = new Date(iso).getTime();
  if (Number.isNaN(target)) return iso;
  const deltaSec = Math.round((target - Date.now()) / 1000);
  const absSec = Math.abs(deltaSec);
  if (absSec < 60) return relativeFormatter.format(Math.round(deltaSec), "second");
  if (absSec < 3600) return relativeFormatter.format(Math.round(deltaSec / 60), "minute");
  if (absSec < 86_400) return relativeFormatter.format(Math.round(deltaSec / 3600), "hour");
  return relativeFormatter.format(Math.round(deltaSec / 86_400), "day");
}
