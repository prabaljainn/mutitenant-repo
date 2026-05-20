# syntax=docker/dockerfile:1.7
#
# CloudGCS admin frontend — Next.js 15 standalone build.
# Two stages: deps+build, then a slim runtime that ships only the
# self-contained `.next/standalone` server.

###############################################################################
# Stage 1: build
###############################################################################
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
# next.config.ts's rewrites() reads PLATFORM_API_BASE at `next build` time
# and bakes the result into the routes manifest (this is the documented
# behaviour for `output: "standalone"` mode). Setting it only at runtime
# is too late — the manifest is fixed by the time the server starts.
# Default to the docker-internal hostname; override with a build-arg for
# alternate deployments.
ARG PLATFORM_API_BASE=http://platform:8080
ENV PLATFORM_API_BASE=$PLATFORM_API_BASE
RUN npm run build

###############################################################################
# Stage 2: runtime
###############################################################################
FROM node:20-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3001
ENV HOSTNAME=0.0.0.0

RUN addgroup -S -g 10002 admin && adduser -S -u 10002 -G admin admin

# Standalone bundle includes server.js + minimal node_modules.
COPY --from=build --chown=admin:admin /app/.next/standalone ./
COPY --from=build --chown=admin:admin /app/.next/static ./.next/static
COPY --from=build --chown=admin:admin /app/public ./public

USER admin

EXPOSE 3001
# Use node itself for the healthcheck — already in the image, no extra
# deps. The minimal node:20-alpine variant doesn't ship wget reliably
# across releases. Exits 0 iff /api/health returns a 2xx.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD node -e "require('http').get('http://127.0.0.1:3001/api/health', r => process.exit(r.statusCode >= 200 && r.statusCode < 300 ? 0 : 1)).on('error', () => process.exit(1))"

CMD ["node", "server.js"]
