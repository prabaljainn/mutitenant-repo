import { initials } from "@/lib/utils/initials";

export function Avatar({ name, size }: { name: string; size?: "sm" | "lg" }) {
  const cls = ["av", size === "sm" ? "av-sm" : size === "lg" ? "av-lg" : ""]
    .filter(Boolean)
    .join(" ");
  return <span className={cls}>{initials(name)}</span>;
}
