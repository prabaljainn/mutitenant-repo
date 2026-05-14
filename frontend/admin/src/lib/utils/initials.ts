/** "Marisol Vega" → "MV". One-or-two letters, uppercased. */
export function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .map((part) => part[0] ?? "")
    .slice(0, 2)
    .join("")
    .toUpperCase();
}
