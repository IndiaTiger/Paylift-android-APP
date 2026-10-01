# PayLift

This repository contains **both** versions of PayLift so they can be compared.

## 📱 Download Demo APK

### [⬇️ Download PayLift Demo APK](https://github.com/IndiaTiger/Paylift-android-APP/raw/main/demo/PayLift-Updated-Demo.apk)

The link downloads [`demo/PayLift-Updated-Demo.apk`](./demo/PayLift-Updated-Demo.apk) directly (about 18 MB). No build tools are needed:

1. Open this GitHub repository on the Android phone (or on a computer, then copy the file to the phone).
2. Tap **Download PayLift Demo APK** above.
3. Wait for the APK to download.
4. Open the downloaded file and install it (allow "Install unknown apps" when Android asks).
5. Open **PayLift**.
6. Sign in with the demo credentials below.

SHA-256: `762c4c82942b981bfc8695fd54f46a9ba26b68daa5c36dd78fc701ce362dd2be` (also in `demo/SHA256SUMS.txt`).

## Demo Login

> **DEMO ONLY.** These credentials work only in the demo APK, never in production.

Phone: `9999999999`

OTP: `123456`

- No SMS is required for the demo; the OTP above is fixed.
- The APK contains the isolated built-in demo engine.
- No laptop or server is required; the demo runs entirely on the phone.
- This does not represent production authentication.
- Real SMS/API providers are intentionally not configured.

## How to Demo

1. Download the APK.
2. Install it on an Android phone.
3. Open PayLift.
4. Enter `9999999999`.
5. Enter OTP `123456`.
6. Continue to Home.
7. Demonstrate:
   - locations (choose pickup and destination)
   - fare calculation
   - booking (top up in Wallet first; the wallet starts at ₹0)
   - pilot simulation
   - ride progress
   - completion
   - wallet
   - history
   - profile

A longer walkthrough is in `demo/README.md`.

## Original Version
Location: the repository root (`app/`, `build.gradle.kts`, … — untouched) and an identical, clearly named snapshot in **`original-version/`** (exact copy of commit `ac07bcd`).

## Updated Version
Location: **`updated-version/`**. See `updated-version/README.md` for build and test instructions.

## Comparison
`docs/ORIGINAL_VS_UPDATED.md`

## Production Status
**FUNCTIONAL DEMO / PRODUCTION-READY ARCHITECTURE, WITH EXTERNAL PROVIDERS AND DEPLOYMENT STILL REQUIRED.**

The following are **not connected** and still require provider accounts, configuration and deployment:
- real payment gateway
- SMS OTP provider
- Google Maps (the in-app map is a schematic)
- telephony (masked calling)
- emergency dispatch (SOS)
- push notifications
- production backend deployment (HTTPS, secrets, database), release signing and a Play Store listing
