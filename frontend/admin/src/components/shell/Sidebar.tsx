"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { meApi } from "@/lib/api/me";
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
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const menuItemsRef = useRef<Array<HTMLElement | null>>([]);

  const closeMenu = useCallback((returnFocus = true) => {
    setMenuOpen(false);
    if (returnFocus) {
      // Return focus to the trigger so keyboard users land back where
      // they started instead of at <body>.
      requestAnimationFrame(() => triggerRef.current?.focus());
    }
  }, []);

  // Outside-click closes the menu. We don't return focus to the trigger
  // here — the user's pointer is already elsewhere by the time this fires.
  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  // Escape anywhere on the page closes the menu and returns focus.
  useEffect(() => {
    if (!menuOpen) return;
    function onKey(e: globalThis.KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        closeMenu(true);
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [menuOpen, closeMenu]);

  // On open, focus the first menu item so arrow keys work immediately.
  useEffect(() => {
    if (!menuOpen) return;
    const first = menuItemsRef.current.find(Boolean);
    if (first) requestAnimationFrame(() => first.focus());
  }, [menuOpen]);

  function onMenuKey(e: KeyboardEvent<HTMLDivElement>) {
    const items = menuItemsRef.current.filter((el): el is HTMLElement => Boolean(el));
    if (items.length === 0) return;
    const i = items.findIndex((el) => el === document.activeElement);
    if (e.key === "ArrowDown") {
      e.preventDefault();
      items[(i + 1 + items.length) % items.length].focus();
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      items[(i - 1 + items.length) % items.length].focus();
    } else if (e.key === "Home") {
      e.preventDefault();
      items[0].focus();
    } else if (e.key === "End") {
      e.preventDefault();
      items[items.length - 1].focus();
    } else if (e.key === "Tab") {
      // Tab out closes the menu without forcibly returning focus —
      // the user is asking to move on, let them.
      setMenuOpen(false);
    }
  }

  function onTriggerKey(e: KeyboardEvent<HTMLButtonElement>) {
    if (!menuOpen && (e.key === "ArrowUp" || e.key === "Enter" || e.key === " ")) {
      // ArrowUp on the trigger opens the menu and lands on the last
      // item — mirrors common combo-box behaviour.
      e.preventDefault();
      setMenuOpen(true);
    }
  }

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
    // Cross-tenant user lookup. Visible to both ADMIN and SUPPORT; the
    // backend scopes SUPPORT results to assigned tenants + tenant users
    // only.
    {
      href: "/users",
      label: "User search",
      icon: Icons.users,
      match: (p) => p.startsWith("/users"),
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

  // Pull the real profile so the sidebar shows the same first/last name
  // the user edited on the Account page. Falls back to the email's local
  // part while the request is in flight or if the endpoint isn't
  // available — this is the same query key the Account page seeds, so
  // it's a free cache hit on most navigations.
  const profile = useQuery({
    queryKey: ["me", "profile"],
    queryFn: () => meApi.getProfile(),
    staleTime: 5 * 60_000,
  });
  const name = useMemo(() => {
    const fn = profile.data?.firstName?.trim() ?? "";
    const ln = profile.data?.lastName?.trim() ?? "";
    const joined = `${fn} ${ln}`.trim();
    if (joined) return joined;
    // Last-resort: derive from the JWT email's local part. Cosmetic
    // only; the Account form is the source of truth.
    return (claims?.email?.split("@")[0] ?? "Operator").replace(/[._-]/g, " ");
  }, [profile.data, claims?.email]);
  const initialsText = initials(name);

  return (
    <aside className="sb">
      <div className="sb-header">
        {/* TODO(brand): replace the "S" placeholder with the real
            CloudGCS / Orochiverse wordmark once the SVG lands in the
            design system. Same swap applies to .auth-brand .sb-logo
            on the auth pages. */}
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

      <div className="sb-footer" ref={menuRef}>
        <button
          ref={triggerRef}
          type="button"
          className={"sb-user-row" + (menuOpen ? " open" : "")}
          onClick={() => setMenuOpen((v) => !v)}
          onKeyDown={onTriggerKey}
          aria-haspopup="menu"
          aria-expanded={menuOpen}
        >
          <div className="sb-avatar">{initialsText}</div>
          <div className="sb-user-meta">
            <div className="sb-footer-name" style={{ textTransform: "capitalize" }}>
              {name}
            </div>
            <div className="sb-footer-sub">Super-admin</div>
          </div>
          <Icon d={Icons.chevUD} size={12} />
        </button>
        {menuOpen && (
          <div className="user-menu" role="menu" onKeyDown={onMenuKey}>
            <Link
              ref={(el) => {
                menuItemsRef.current[0] = el;
              }}
              href="/account"
              className="user-menu-row"
              role="menuitem"
              onClick={() => closeMenu(false)}
            >
              <Icon d={Icons.settings} size={14} />
              <span>Account settings</span>
            </Link>
            <button
              ref={(el) => {
                menuItemsRef.current[1] = el;
              }}
              type="button"
              className="user-menu-row user-menu-row--danger"
              role="menuitem"
              onClick={() => {
                closeMenu(false);
                void signOut();
              }}
            >
              <Icon d={Icons.switch} size={14} />
              <span>Sign out</span>
            </button>
          </div>
        )}
      </div>
    </aside>
  );
}
