# Production readiness

Status: **NOT PRODUCTION READY**. The code is complete and verified locally. External integrations and deployment are not done.

## A. Code complete (verified in this repository)
* Server-authoritative wallet ledger: integer paise, immutable entries, unique references, atomic conditional updates, a hold → capture/release model, and no client endpoint that changes a balance.
* Payments: idempotent orders, signature verification, signed and deduplicated webhooks, server-side amount validation, operator-only bounded refunds. The full 10-scenario test matrix passes.
* Ride state machine: guarded compare-and-set transitions, one active ride per rider and per pilot, recovery after process death.
* Auth: OTP login (rate-limited, 5 attempts per code, single use), rotating refresh tokens with reuse detection, logout, account deletion.
* Start-ride OTP brute-force limit: persistent counter, locks after 5 failures for 5 minutes, concurrency-safe, generic errors.
* Production/test separation: the server refuses test adapters when `NODE_ENV=production`; the release build refuses test providers; test code is absent from the release APK/AAB (verified in the dex).
* Room cache with a non-destructive v1→v2 migration; exported schemas; migration test.
* Tests: backend 56/56; Android 64/64 (unit, integration, end-to-end vs the backend, ViewModel, UI states, screenshots); lint 0 errors; R8 release APK and AAB build; secret scan clean.

## B. External integrations required
1. Payment gateway: a server adapter in `backend/src/payments.js` (create order / verify signature / verify webhook / refund) and the gateway SDK as the client `PaymentCheckout` in `app/src/release/.../VariantModule.kt`. Credentials: `PAYMENT_KEY_ID`, `PAYMENT_KEY_SECRET`, `PAYMENT_WEBHOOK_SECRET` (server) and `PAYMENT_PUBLIC_KEY` (app).
2. OTP/SMS provider: an adapter in `backend/src/auth.js` (`otpSenders`), selected with `AUTH_PROVIDER`.
3. Maps: `MAPS_PROVIDER=google` + `MAPS_SERVER_KEY` (adapter written, untested against the live API) and a Maps SDK view in the app (`MAPS_API_KEY`). The in-app map is still a schematic canvas.
4. Telephony (masked calls), emergency dispatch (SOS), push notifications (ride updates currently poll).
5. A pilot app (the backend pilot endpoints exist).

## C. Deployment required
1. Host the backend behind HTTPS with managed secrets (`JWT_SECRET`, `OTP_PEPPER`, `ADMIN_API_TOKEN`, provider secrets), a production database with backups, logging and monitoring.
2. Edge protection: per-IP / per-account rate limiting for `/auth/*` and `/auth/refresh` (only per-phone and per-OTP limits exist in code), plus a WAF.
3. Webhook endpoint registered with the gateway; operator tooling for refunds.
4. Release signing keystore (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), Play Console, and legal review of the DRAFT policy texts.
5. Set `BACKEND_BASE_URL` (https), `PAYMENT_PROVIDER`, `PAYMENT_PUBLIC_KEY`, `MAPS_API_KEY`, `AUTH_PROVIDER` for the release build.

## Known residual risks (code)
* OTP login: an attacker can request 5 codes per 15 min per phone with 5 guesses each (~25 guesses / 15 min against 10⁶). Acceptable with edge rate limiting (C.2); without it, add per-IP limits.
* Ledger pagination is timestamp-only.
