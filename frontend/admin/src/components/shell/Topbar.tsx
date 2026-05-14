"use client";

import Link from "next/link";
import { Fragment, type ReactNode } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";

export type Crumb = { label: string; href?: string };

export function Topbar({ crumbs, children }: { crumbs: Crumb[]; children?: ReactNode }) {
  return (
    <div className="tb">
      <div className="tb-crumbs">
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1;
          return (
            <Fragment key={i}>
              {i > 0 && <Icon d={Icons.chevR} size={12} />}
              {c.href && !last ? (
                <Link
                  href={c.href}
                  style={{ color: "inherit", textDecoration: "none" }}
                >
                  {c.label}
                </Link>
              ) : (
                <span className={last ? "crumb-current" : ""}>{c.label}</span>
              )}
            </Fragment>
          );
        })}
      </div>
      <div className="grow" />
      {children}
    </div>
  );
}
