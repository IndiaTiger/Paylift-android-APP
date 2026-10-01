# Original vs Updated PayLift

* **Original:** repository root and `original-version/` (exact `git archive` of commit `ac07bcd`). The original commits are untouched in history.
* **Updated:** `updated-version/`. **Demo APK:** `demo/`.

| Area | Original version | Updated version |
|---|---|---|
| **UI** | Compose screens: booking, wallet, activity, profile and modals | **Same design** (colours, fonts, spacing, navigation, components). Header and nav bar are pixel-identical; other screens differ only in truthful text, exact ₹ amounts, and necessary new states (sign-in, loading/offline/retry). Details: `updated-version/docs/UI_CHANGES.md` |
| **Login / auth** | None; a hardcoded user ("Mohit Tyagi") | Phone OTP (rate-limited, 5 attempts, single use), rotating refresh tokens with reuse detection, logout, account deletion. Demo credentials only in the demo build / dev backend |
| **Wallet** | Balance stored on the phone (Double), ₹650 free starting money, auto top-up that created money | Server ledger in integer paise: immutable entries, unique references, holds; starts at ₹0; the phone only caches server data |
| **Payments** | Fake: the app "verified" its own HMAC with a hardcoded secret | Order → gateway checkout → server signature verification → signed, deduplicated webhook → idempotent credit; server-side amount validation; operator-only bounded refunds |
| **Ride lifecycle** | Timer animation; money settled by the app | Server state machine (12 states, guarded compare-and-set), fare held on request and charged on completion, cancel releases the hold, no-pilot timeout |
| **Database** | Room v1, destructive migration fallback, no schema export | Room v2 cache, explicit non-destructive v1→v2 migration (old data kept as `legacy_v1_*`), exported schemas, migration test |
| **Security** | Hardcoded HMAC secret, backups enabled, `CALL_PHONE`, no R8 | No secrets in app/APK (scanned), Keystore-encrypted session, backups off, R8 on, release refuses test providers, start-ride OTP brute-force lockout |
| **API architecture** | None (all local) | UI → ViewModel → use cases → repositories → service interfaces → Retrofit API; contract in `updated-version/docs/API.md` |
| **Backend** | None | `updated-version/backend`: Node + SQLite reference/test backend (auth, users, wallet, payments, rides, locations, pilots, safety) |
| **Testing** | 1 sample test | Backend 62 tests; Android 72 tests (unit, MockWebServer, Room/migration, ViewModel, UI states, screenshots, end-to-end vs backend, demo engine); lint; secret scan; CI workflow |
| **Error handling** | Exceptions swallowed; success shown regardless | Every operation has Loading / Failed / Offline / Timeout / Retry states; "not configured" shown truthfully |
| **Process death** | Active ride lost | Active ride, in-flight ride request (same idempotency key) and unconfirmed payments are recovered |
| **Production config** | Secrets plugin, Gemini key slot | Whitelisted public keys only (`.env.example`, all empty); server secrets only on the server; production refuses test adapters |
| **AI** | Firebase AI (Gemini) dependency and metadata capability | **Removed.** No AI anywhere |
| **Claims** | "HMAC verified", "Police notified", "Broadcasted", "Masked call", "Verified" | Only truthful states; SOS/calling report "not connected" |
| **External integrations** | None (simulated as if real) | Interfaces ready; real gateway, SMS, maps, telephony, emergency dispatch and push **not connected** |
| **Demo** | Always "working" because everything was fake | `demo/PayLift-Updated-Demo.apk`: in-app demo engine with the same rules, clearly labelled "Demo Mode" |

**Status:** FUNCTIONAL DEMO / PRODUCTION-READY ARCHITECTURE, WITH EXTERNAL PROVIDERS AND DEPLOYMENT STILL REQUIRED.
