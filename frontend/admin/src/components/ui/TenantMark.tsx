// The square gradient initial block used for tenants. Distinct from <Avatar/>
// (circular, person-initials) so listings stay scannable at a glance.

import { type CSSProperties } from "react";

export function TenantMark({
  mark,
  size = 26,
  radius,
  fontSize,
}: {
  mark: string;
  size?: number;
  radius?: number;
  fontSize?: number;
}) {
  const style: CSSProperties = {
    width: size,
    height: size,
    fontSize: fontSize ?? Math.max(10, Math.round(size * 0.4)),
    borderRadius: radius ?? Math.max(5, Math.round(size * 0.2)),
  };
  return (
    <div className="sb-tenant-mark" style={style}>
      {mark}
    </div>
  );
}
