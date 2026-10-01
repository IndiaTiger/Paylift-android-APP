# PayLift — Updated Demo APK

> **This is a functional DEMO build, not a production build.** No real money moves, no SMS is sent, and nobody (police, emergency contacts or drivers) is contacted.

| | |
|---|---|
| File | `PayLift-Updated-Demo.apk` |
| Package | `com.aistudio.paylift.rhwe.demo` (installs alongside any other PayLift build) |
| Version | `1.0-demo`, min Android 7.0 (API 24), target API 36 |
| Signed with | the standard Android **debug** certificate (demo only, not for Play Store) |
| SHA-256 | see `SHA256SUMS.txt` |

## Demo credentials (DEMO ONLY)

| Field | Value |
|---|---|
| Phone | `9999999999` |
| OTP | `123456` |

They work **only** in this demo build: the release build cannot contain the demo engine, and the real backend refuses demo credentials when `NODE_ENV=production`. Any other number shows "SMS is not connected". Wrong codes are rejected and lock the login after 5 attempts, like the real OTP flow.

## Backend requirement

**None.** The APK contains an in-process **demo engine** (`updated-version/app/src/devShared/.../DemoEngine.kt`) that applies the same rules as the PayLift backend: wallet ledger with fare hold/charge/release, signature-verified payments, the guarded ride state machine and a pilot simulator. It works offline, with no laptop or server.

(For a real client/server demo, run the Node test backend in `updated-version/backend` with `npm run start:dev` and use the `debug` build instead.)

## Install
1. Copy `PayLift-Updated-Demo.apk` to an Android phone (or `adb install PayLift-Updated-Demo.apk`).
2. Allow "Install unknown apps" for your file manager or browser when Android asks.
3. Optionally check the hash: `sha256sum -c SHA256SUMS.txt`.

## Demo flow (about 3 minutes)
1. **Login:** enter `9999999999` → **Send code** → enter `123456` → **Verify & continue**.
2. **Home:** note the wallet shows **₹0**, because new accounts get no free money.
3. **Wallet → +₹500:** the *Demo Gateway* checkout is signature-verified, then the balance is credited and a ledger entry appears.
4. **Ride:** tap *Pickup*, choose *Clock Tower*; tap *Destination*, choose *Rajpur Road*. The exact fare (e.g. ₹194.25) appears; tap **Fare & 85% Pilot Split** to see the breakdown.
5. **Confirm ride:** the fare is **held** (Wallet → "Locked in Escrow"). The simulator then assigns a pilot, who accepts, arrives (the rider's start PIN is shown), starts the trip and moves along the route.
6. **Completion:** the receipt shows the **charged amount = displayed fare**; rate the pilot.
7. **Wallet / Activity / Profile:** ledger (top-up, hold, charge), trip history, and the emergency contact you can edit.
8. **Truthful states:** *SOS* says emergency dispatch is not connected and offers to dial 112; *Call* says calling is unavailable. Cancelling before pickup releases the hold.

## Not connected (intentionally)
Real payment gateway, SMS OTP, Google Maps (the map is a schematic), masked calling, emergency dispatch, push notifications, production backend. See `../docs/ORIGINAL_VS_UPDATED.md` and `../updated-version/docs/PRODUCTION_READINESS.md`.
