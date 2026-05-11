# Platform reference

Reference material for the Orochiverse multi-tenant platform. Aimed at someone who already knows what the platform is for (see `docs/architecture/01-system-overview.md`) and wants to look up *what exists* and *where*.

| File | What's in it |
|---|---|
| [`classes.md`](classes.md) | Every production class, organized by package, with a one-line role + key methods. |
| [`services.md`](services.md) | Runtime view: processes, ports, Docker services, startup order, profiles. |
| [`tests.md`](tests.md) | Test inventory: every test class with what it verifies and how to run it. |
| [`configuration.md`](configuration.md) | Every settable property + env var, by profile. |
| [`roles.md`](roles.md) | UserKind / OperatorRole / TenantRole hierarchy + endpoint authorization matrix. |

For higher-level context:
- `docs/architecture/` — design docs (system overview, multi-tenancy, auth, email, integration)
- `docs/superpowers/specs/2026-05-11-platform-shell-m1-design.md` — the M1 design spec these docs implement
- `docs/features/roadmap.md` — milestones beyond M1

## Conventions used in this reference

- **`common.*`** = shared infrastructure. Cannot depend on `iam`, `tenant`, or `gcs` (enforced by `PackageBoundaryTest`).
- **`iam.*`** = operator + tenant-user identity. Lives in the shared `iam_db` Mongo database.
- **`tenant.*`** = tenant-admin self-service surface. Lives in per-tenant `tenant_<id>_db`s.
- **`gcs.*`** = M2+ placeholder for the drone / mission domain.

A class flagged "phase X.Y" was added in that phase. Phases are listed in the M1 design spec §9.
