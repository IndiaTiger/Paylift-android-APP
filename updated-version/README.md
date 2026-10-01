# PayLift

Android ride-hailing client (Jetpack Compose) with a server-authoritative wallet ledger, payments and ride lifecycle, plus a zero-dependency **test/reference backend** (`backend/`, Node ≥ 22.13 + built-in SQLite).

> Status: the client and the backend contracts are implemented and tested end to end against the local test backend.
> **Real production providers (payment gateway, OTP/SMS, maps, telephony, emergency dispatch) are not connected** — see *Production placeholders* below.

## Architecture

```
Compose UI (unchanged screens)
  → PayLiftViewModel            ui/PayLiftViewModel.kt          (Loading/Failed/Offline/Retry state per operation)
    → Use cases                 usecase/UseCases.kt            (validation, guards)
      → Repositories            repository/Repositories.kt     (server-first writes, Room cache, crash recovery)
        → Service interfaces    services/Services.kt           (Auth, User, Wallet, Payment, PaymentCheckout, Location, Maps, Ride, Pilot, Notification, Safety)
          → Remote adapters     services/remote/*              (HTTP)   | device/AndroidLocationService (GPS)
            → API layer         api/PayLiftApi.kt, ApiClient.kt (Retrofit/Moshi, auth + single-flight token refresh, error mapping)
              → Backend         backend/  — contract: docs/API.md
```

* Wiring: `di/AppContainer.kt`. Build-variant pieces live in `src/debug/.../VariantModule.kt` (test-gateway checkout, HTTP logging) and `src/release/.../VariantModule.kt` (no test code; payments report *not configured* until a real gateway SDK is added). The release APK cannot contain the test gateway because the class is not in its source set (CI checks the dex).
* Money: integer paise end to end (`Long`). Rupee `Double`s exist only as display conversions.
* Room (`data/`) is a **cache** of server state plus crash-recovery records (pending ride request idempotency key, pending payments, active ride pointer). It is never authoritative for money. Migration v1→v2 is explicit and non-destructive (see `PayLiftDatabase.MIGRATION_1_2`); schemas are exported to `app/schemas/`.

## Configuration

Client (compiled into the APK — **public values only**): copy `.env.example` to `.env` or set environment variables. Only the whitelisted keys are copied into `BuildConfig` (`app/build.gradle.kts`, `CLIENT_CONFIG_KEYS`).

| Key | Purpose | Production value |
|---|---|---|
| `BACKEND_BASE_URL` | PayLift backend, must be `https://` for release | **empty — to be provided** |
| `PAYMENT_PROVIDER` | Gateway id (`test` is rejected for release) | **empty** |
| `PAYMENT_PUBLIC_KEY` | Gateway publishable key (e.g. Razorpay `key_id`) | **empty** |
| `MAPS_API_KEY` | App-restricted Maps SDK key | **empty** |
| `PLACES_API_KEY` | Only if calling Places directly (default: backend proxy) | **empty** |
| `ROUTING_API_KEY` | Only if calling routing directly (default: backend proxy) | **empty** |
| `AUTH_PROVIDER` | OTP provider id (`dev` is rejected for release) | **empty** |

Server (secrets — never in the app): `backend/.env.example` lists `JWT_SECRET`, `OTP_PEPPER`, `PAYMENT_KEY_SECRET`, `PAYMENT_WEBHOOK_SECRET`, `MAPS_SERVER_KEY`, `ADMIN_API_TOKEN`, etc. With `NODE_ENV=production` the server refuses to start without secrets or with any test adapter.

Release signing: set `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` in the release environment; otherwise release artifacts are built unsigned. Keystores are git-ignored.

## Demo build

`./gradlew :app:assembleDemo` builds the self-contained demo APK (build type `demo`, in-app DemoEngine, demo credentials `9999999999` / `123456`). The demo code lives in `src/demo` + `src/devShared` and cannot be part of release builds. A prebuilt copy is in `../demo/`.

## Running locally

```bash
# 1. Test backend (test adapters: dev OTP, test payment gateway, dev maps, pilot simulator)
cd backend && npm run start:dev          # listens on 0.0.0.0:8080, see backend/.env.test

# 2. App (debug build talks to http://10.0.2.2:8080/ from the emulator; override with DEV_BACKEND_BASE_URL)
./gradlew :app:installDebug
```

Sign in with any `+91` number; the dev backend returns the OTP and the app shows it as "Test backend code". Top up via the *Test Gateway*, book a ride, and the dev pilot simulator drives it through `ASSIGNED → … → COMPLETED` using the same guarded transitions a pilot app would call.

## Tests

```bash
cd backend && npm test                   # backend: auth, payments (10-scenario matrix), ledger, rides, concurrency
./gradlew :app:testDebugUnitTest         # unit + integration (MockWebServer, Room, migration) + end-to-end vs real backend + Compose UI (Robolectric)
./gradlew :app:verifyRoborazziDebug      # UI regression vs the original prototype screenshots
./gradlew :app:lintDebug :app:assembleRelease :app:bundleRelease
python scripts/secret_scan.py app/build/outputs   # repository + APK/AAB secret scan
```

CI: `.github/workflows/ci.yml`. No Play Store upload is automated.

## Production placeholders / what is still required

See `docs/PRODUCTION_READINESS.md`.
