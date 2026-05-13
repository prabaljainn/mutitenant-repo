# scripts/

One-liners for the local dev loop. All scripts read `deployment/.env`
(auto-created from `.env.example` on first run) so config is in one place.

| Script | What it does |
|---|---|
| `dev-up.sh [--profile proxy]` | Start MongoDB 8 (replica set), Redis, Mailhog. Waits until rs0 is PRIMARY before returning. |
| `dev-down.sh` | Stop the dev stack. Volumes (data) preserved. |
| `dev-reset.sh` | Stop AND wipe volumes. Asks for confirmation. |
| `dev-logs.sh [service]` | Tail logs from all services, or one. |
| `dev-status.sh` | Container status + connectivity checks for Mongo / Redis / Mailhog. |
| `run-app.sh [--debug] [--suspend] [--no-deps]` | Run the Spring Boot app with the right Mongo/Redis/Mailhog wiring. `--debug` opens JDWP on `$APP_DEBUG_PORT` (default 5005). `--suspend` makes the JVM wait for a debugger to attach before booting. |
| `dev-rerun.sh [--reset] [--debug] [--no-app]` | One-shot: kill any process on `$APP_PORT`, restart the Docker stack, re-run the app. `--reset` also wipes Mongo + Redis volumes (drops `iam_db` + every `tenant_<id>_db`). `--no-app` restarts infra only. |

## Typical day

```bash
./scripts/dev-up.sh        # start services
./scripts/run-app.sh       # run the app on :8080
# … work …
./scripts/dev-down.sh      # done
```

## "Rerun everything"

```bash
./scripts/dev-rerun.sh           # frees :8080, restarts stack, runs app
./scripts/dev-rerun.sh --reset   # ALSO wipes Mongo data — back to a fresh iam_db
./scripts/dev-rerun.sh --debug   # rerun with JDWP on :5005
./scripts/dev-rerun.sh --no-app  # only restart infra
```

## Remote debugging from IntelliJ / VS Code

1. Run the app with `--debug`:
   ```bash
   ./scripts/run-app.sh --debug
   ```
2. In IntelliJ: **Run → Edit Configurations → + → Remote JVM Debug**
   - Host: `localhost`
   - Port: `5005` (or whatever you set in `APP_DEBUG_PORT`)
3. In VS Code: add to `.vscode/launch.json`:
   ```json
   {
     "type": "java",
     "name": "Attach to platform",
     "request": "attach",
     "hostName": "localhost",
     "port": 5005
   }
   ```
4. Start the debugger. Set breakpoints. Hit endpoints.

Use `--suspend` if you need to debug startup issues — the JVM waits for the
debugger before initialising Spring.

## Configuration

Every port and version is in `deployment/.env`:

```
MONGO_VERSION=8.0      # bump to 8.3 for rapid release line
APP_PORT=8080
APP_DEBUG_PORT=5005
REDIS_PORT=6379
…
```
