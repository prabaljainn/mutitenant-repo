// Process liveness probe — used by the Dockerfile HEALTHCHECK. Intentionally
// has nothing to do with the Spring backend; if Next.js can serve this, the
// container is healthy.

export function GET() {
  return Response.json({ status: "UP", app: "cloudgcs-admin" });
}
