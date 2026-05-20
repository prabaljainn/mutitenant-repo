import type { NextConfig } from "next";

const config: NextConfig = {
  reactStrictMode: true,
  output: "standalone",
  // `experimental.typedRoutes` is intentionally OFF — Turbopack (which dev mode
  // uses) doesn't support it as of Next 15.1.x, and the value-add is small
  // because the route surface is tiny and stable.
  async rewrites() {
    const raw = process.env.PLATFORM_API_BASE?.trim();
    if (!raw) return [];
    // Strip trailing slashes so PLATFORM_API_BASE=https://api.example.com/ and
    // ...example.com (no slash) produce the same destination. A double slash
    // here would otherwise yield //api/... and 404 on most backends.
    const backend = raw.replace(/\/+$/, "");
    if (!/^https?:\/\//.test(backend)) {
      throw new Error(
        `PLATFORM_API_BASE must start with http:// or https:// — got "${raw}"`
      );
    }
    // Log once at startup so it's obvious in the dev console where /api/* lands.
    // eslint-disable-next-line no-console
    console.log(`[next.config] proxying /api/{auth,admin,tenant}/* → ${backend}`);
    return [
      // The frontend always talks same-origin; the rewrite handles cross-origin to
      // the Spring service so cookies + CSRF semantics stay simple, and the browser
      // never sees the backend's hostname (no CORS preflight, no mixed-content).
      //
      // Two admin prefixes are forwarded — Spring exposes operator-facing CRUD
      // under `/admin/api/*` (TenantsAdminController.java etc.), while the SPA
      // historically called `/api/admin/*`. Both shapes proxy through so we can
      // settle on one without breaking the other mid-flight.
      { source: "/api/auth/:path*", destination: `${backend}/api/auth/:path*` },
      { source: "/admin/api/:path*", destination: `${backend}/admin/api/:path*` },
      { source: "/api/admin/:path*", destination: `${backend}/api/admin/:path*` },
      { source: "/api/tenant/:path*", destination: `${backend}/api/tenant/:path*` },
    ];
  },
};

export default config;
