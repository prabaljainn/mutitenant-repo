"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isOperatorAdmin } from "@/lib/auth/jwt";
import { initials } from "@/lib/utils/initials";

type NavLink = { href: string; label: string; icon: string; count?: number | string; match?: (path: string) => boolean };

export function Sidebar({
  tenantCount,
  operatorCount,
}: {
  tenantCount?: number;
  operatorCount?: number;
}) {
  const pathname = usePathname() ?? "";
  const { claims, signOut } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  const links: NavLink[] = [
    { href: "/overview", label: "Overview", icon: Icons.dashboard },
    {
      href: "/tenants",
      label: "Tenants",
      icon: Icons.building,
      count: tenantCount,
      match: (p) => p.startsWith("/tenants"),
    },
    {
      href: "/operators",
      label: "Operators",
      icon: Icons.shield,
      count: operatorCount,
      match: (p) => p.startsWith("/operators"),
    },
    // Audit is admin-only on the backend; hide it for SUPPORT entirely
    // rather than render a link that 403s.
    ...(isOperatorAdmin(claims)
      ? [
          {
            href: "/audit",
            label: "Audit",
            icon: Icons.console,
            match: (p: string) => p.startsWith("/audit"),
          },
        ]
      : []),
    { href: "/settings", label: "Settings", icon: Icons.settings },
  ];

  const name = (claims?.email?.split("@")[0] ?? "Operator").replace(/[._-]/g, " ");
  const initialsText = initials(name);

  return (
    <aside className="sb">
      <div className="sb-header">
        <div className="sb-logo">S</div>
        <div className="sb-logo-text" style={{ flex: 1, minWidth: 0 }}>
          CloudGCS
          <small>Admin Console</small>
        </div>
      </div>

      <div className="sb-section-title">Console</div>
      {links.map((l) => {
        const active = l.match ? l.match(pathname) : pathname === l.href;
        return (
          <Link
            key={l.href}
            href={l.href}
            className={"sb-item" + (active ? " active" : "")}
            aria-current={active ? "page" : undefined}
          >
            <span className="sb-icon">
              <Icon d={l.icon} size={16} />
            </span>
            <span className="sb-label">{l.label}</span>
            {l.count != null && <span className="sb-count">{l.count}</span>}
          </Link>
        );
      })}

      <div className="sb-footer" ref={menuRef} style={{ position: "relative" }}>
        <div
          className="sb-footer-text"
          style={{ cursor: "pointer", borderRadius: 6 }}
          onClick={() => setMenuOpen((v) => !v)}
        >
          <div className="sb-avatar">{initialsText}</div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="sb-footer-name" style={{ textTransform: "capitalize" }}>
              {name}
            </div>
            <div className="sb-footer-sub">Super-admin</div>
          </div>
          <Icon d={Icons.chevUD} size={12} />
        </div>
        {menuOpen && (
          <div className="sb-switcher" style={{ bottom: "calc(100% + 6px)", top: "auto" }}>
            <div className="sb-switcher-row" onClick={() => setMenuOpen(false)}>
              <Icon d={Icons.settings} size={14} /> <span>Account settings</span>
            </div>
            <div
              className="sb-switcher-row"
              onClick={() => {
                setMenuOpen(false);
                void signOut();
              }}
            >
              <Icon d={Icons.switch} size={14} /> <span>Sign out</span>
            </div>
          </div>
        )}
      </div>
    </aside>
  );
}
