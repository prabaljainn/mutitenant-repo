# Postman + Swagger access

Two ways to drive the platform from a desktop GUI: **Swagger UI** (browser, no install) and **Postman** (collection + environment included here).

## Default dev credentials

The dev profile auto-seeds an OPERATOR_ADMIN on first boot when `iam_db.users` is empty. These ship in `application-dev.yml`:

```
email:    admin@orochiverse.local
password: ChangeMe123!
```

The `BootstrapOperatorRunner` only seeds when no operator exists, so you can keep these settings indefinitely — no duplicates after the first boot.

**Production note:** `application-prod.yml` does NOT set these. Inject `PLATFORM_BOOTSTRAP_OPERATOR_EMAIL` and `PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD` env vars in your deploy, log in once, then unset them and rotate the password through `PUT /admin/api/operators/{id}` (Phase 1.7+) or the password-change flow (Phase 1.9).

---

## Boot the stack

```bash
./scripts/dev-up.sh        # Mongo (rs0) + Redis + MailHog
./scripts/run-app.sh       # platform on :8080
```

On a fresh boot you'll see this in the log:

```
WARN  ... Bootstrap operator created — email=admin@orochiverse.local role=OPERATOR_ADMIN
       id=bootstrap-operator-admin. Rotate the bootstrap password immediately and unset
       the env vars.
```

If you see `Bootstrap operator skipped: at least one OPERATOR user already exists`, the platform was booted previously and the admin is already there.

---

## Swagger UI

Open http://localhost:8080/swagger-ui.html

**Authorize flow:**
1. Expand **Auth → POST /api/auth/login**, click **Try it out**, send the dev creds. Copy `accessToken` from the response.
2. Click the green **Authorize** button at the top right. Paste the token (Swagger prepends `Bearer ` for you). Click **Authorize**, then **Close**.
3. Every subsequent call from Swagger UI now sends `Authorization: Bearer <token>`.

The OpenAPI JSON is at http://localhost:8080/v3/api-docs if you want to feed it into another tool.

---

## Postman

### Import

In Postman: **File → Import** and drop in both files from this directory:

- `orochiverse-platform.postman_collection.json` — all M1 endpoints
- `orochiverse-local.postman_environment.json` — `baseUrl` + dev admin creds + token slots

Then top-right **environment selector** → choose **"Orochiverse Local (dev)"**.

### Usage flow

1. **Auth → Login (admin)** — uses `{{adminEmail}}` / `{{adminPassword}}` from the env. The test script captures `accessToken` and `refreshToken` into the env automatically.
2. **Admin / Tenants → Create tenant** — id defaults to `acme` (env var `tenantId`). Returns 201 + tenant body. Tenant DB `tenant_acme_db` is provisioned in Mongo on the way.
3. **Admin / Operators → Invite operator** — creates a SUPPORT operator in `INVITED` status. Captures `operatorId` into the env. (They can't log in until invite-accept lands in Phase 1.9.)
4. **Admin / Operator Assignments → Grant assignment** — gives `{{operatorId}}` access to `{{tenantId}}`.
5. **Auth → Switch tenant** — operator-only. Server checks the assignment, mints a new access token whose `tid` claim is the requested tenant. The captured `accessToken` is replaced; subsequent admin calls operate in that tenant context.
6. **Auth → Refresh** — returns a new access + refresh pair. Old refresh token is single-shot — invalidated on use.
7. **Auth → Logout** — revokes the captured refresh token; clears both env vars.

### Auth model recap

- **Collection-level auth** is `Bearer {{accessToken}}`. Every request inherits it unless the request itself overrides — the public ones (Login, Refresh, JWKS, Actuator) override to `noauth`.
- **Switch-tenant** replaces `accessToken` so following calls see the new `tid` in `Me`.
- **Logout** clears `accessToken` and `refreshToken` from the env so accidental replays fail clean.

### Environment variables

| Variable | Default | Notes |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | Change to your deployment URL. |
| `adminEmail` | `admin@orochiverse.local` | Matches `application-dev.yml` bootstrap. |
| `adminPassword` | `ChangeMe123!` | Same. **Rotate in any environment that's not your laptop.** |
| `accessToken` | (empty) | Set by Login / Refresh / Switch-tenant. |
| `refreshToken` | (empty) | Set by Login / Refresh. Cleared by Logout. |
| `tenantId` | `acme` | Used as the path / body param in tenant + assignment requests. Bumped by Create-tenant if the response id differs. |
| `operatorId` | (empty) | Set by Invite-operator. |

### Common errors

| Symptom | Likely cause |
|---|---|
| `401 unauthorized` on a protected endpoint | `accessToken` is empty or expired (15-min TTL). Run **Refresh** or re-Login. |
| `401 invalid_credentials` on Login | Wrong `adminEmail`/`adminPassword`, or the bootstrap runner skipped (no env, no dev-yml defaults). Check the app log. |
| `409 conflict` on Create tenant | A tenant with that id already exists — pick another id or DELETE the existing one first. |
| `403 forbidden` on a write | You're logged in as `OPERATOR_SUPPORT` but the endpoint requires `OPERATOR_ADMIN`. Switch operator. |
| `403 operator_not_assigned` on Switch-tenant | The current operator has no `OperatorAssignment` for that tenant — Grant one first. |
| `404 not_found` on Tenant get/update | The tenant id doesn't exist in `iam_db.tenants`. List them first. |

---

## See also

- `docs/reference/services.md` — runtime view, profiles, ports
- `docs/reference/classes.md` — every class, organized by package
- `docs/reference/tests.md` — test inventory + how to run
- `docs/reference/configuration.md` — every property + env var
