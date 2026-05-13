# MongoDB init scripts

This directory is mounted into the `mongo` container at
`/docker-entrypoint-initdb.d/` and the official image runs every `.js` /
`.sh` file here **on first container start only** (when `/data/db` is empty).

## What runs where

| Concern | Owned by |
|---|---|
| Replica-set initiation (`rs.initiate`) | `mongo-init` service in `docker-compose.yml` (runs every boot, idempotent) |
| Database & user provisioning | Not used in dev — Mongo creates DBs lazily on first write, dev auth is disabled |
| Indexes / schema | The Spring Boot app via **Mongock** changesets (Phase 1.4+) |

This split keeps schema in Java alongside the entities (versioned with the
code) and keeps infra concerns (replica set, auth in prod) in the deployment
layer. If you find yourself wanting to add a `.js` here, ask first whether
it belongs in a Mongock changeset instead.
