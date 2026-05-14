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
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:3001/api/health || exit 1

CMD ["node", "server.js"]
