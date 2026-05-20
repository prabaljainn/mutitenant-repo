import { NextResponse, type NextRequest } from "next/server";

// Bare-minimum routing middleware:
//   - `/`        → `/overview` (the admin layout takes over from there and
//                  bounces anon visitors to /login client-side)
//
// Note we do NOT enforce auth here — the access token lives in localStorage,
// which the middleware can't read. Server-side auth would require a refresh
// cookie set by a Next route handler; that's a follow-up. The /admin layout's
// useAuth() check is what protects every screen for now.

export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  if (pathname === "/") {
    const url = req.nextUrl.clone();
    url.pathname = "/overview";
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/"],
};
