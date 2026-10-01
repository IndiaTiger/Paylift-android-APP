# UI changes vs the original prototype

Reference images of the ORIGINAL UI: `docs/ui-baseline-original/` (recorded before any change).
Current approved references: `app/src/test/screenshots/` (checked by `./gradlew verifyRoborazziDebug`).
Comparison run (`compareRoborazziDebug`) with identical inputs:

| Screen | Result |
|---|---|
| Header | pixel-identical |
| Bottom nav bar | pixel-identical |
| Booking | layout/colours/spacing identical; text only (see below) |
| Wallet | layout identical; auto-top-up sliders hidden (feature disabled); text only |
| Fare sheet | layout identical; text only |
| History | amount shows 2 decimals |
| Profile | text only; FAQ answers shorter (content moves up one line) |

No colours, typography, icons, spacing, animations, navigation or components were changed.
A regression found during comparison (the "no pilots nearby" label wrapping and growing the vehicle row) was fixed by shortening the label to "ETA —".

## Intentional, necessary changes
* **Money precision:** every charged amount is shown with 2 decimals (₹194.25, not "₹194"/"₹194.3") so the displayed amount equals the charged amount.
* **Vehicle rows:** the selected vehicle shows the server quote; the others show `~` estimates. The ETA comes from real nearby pilots ("ETA —" when unknown) instead of a hardcoded number.
* **Wallet:** the fake gateway chips (Razorpay/Cashfree/Stripe/Mock Sandbox) were replaced by the configured provider; "HMAC-256" became "SERVER LEDGER" and "Dehradun Verified Account" became "Phone-verified account". Auto top-up is disabled, labelled "Needs a saved payment mandate. Not available yet.", and its sliders are hidden. The "Auto-Refill" filter became "Holds". Export uses the share sheet instead of the clipboard.
* **Fare sheet:** "Platform Safety & 24x7 Escrow Fee" became "Platform Safety Fee", and "Covers Escrow lock, Maps & 24/7 SOS" became "Covers platform operations and support".
* **Profile:** the avatar initials come from the name instead of a hardcoded "AM". The FAQ and policy subtitles are truthful. Sign-out and account-deletion rows were added in the existing row style.
* **SOS modal:** shows the real result ("CALL 112 NOW" and "No alert was sent automatically" when unavailable) instead of "SOS PROTOCOL BROADCASTED ✓". Share Trip shares a map link instead of a fake tracking URL, and the audio log is marked "(soon)".
* **Masked call:** no fake running timer; the call is shown as "Call unavailable" until a telephony provider exists.
* **Chat:** "End-to-End Escrow Encrypted" became "Messages relayed via PayLift".
* **Receipt:** "ESCROW DEBITED ✓" became "CHARGED FROM WALLET ✓" (or "NOT CHARGED" / "CANCELLATION FEE").
* **Ride HUD:** the cancel label is truthful, and speed shows "—" when unknown.
* **Legal texts:** rewritten to describe actual behaviour and marked DRAFT.
* **New states:** a sign-in screen (app theme tokens), an offline/unavailable banner with Retry, a fare-loading/error row with Retry, a place search field and a "Current location" chip in the pickers, long-press to pin on the map, and loading spinners on Add Funds and confirm.

## UI files changed
`MainActivity.kt`, `ui/screens/LoginScreen.kt` (new), `RideBookingScreen.kt`, `WalletEscrowScreen.kt`, `ActiveRideHUD.kt`, `ProfileSettingsScreen.kt`, `PastRidesScreen.kt`, `components/InteractiveMapCanvas.kt`, `modals/SafetyCenterModal.kt`, `modals/MaskedCallModal.kt`, `modals/InAppChatModal.kt`, `modals/LegalCenterModal.kt`, `modals/RideReceiptModal.kt`, `modals/FareBreakdownSheet.kt`.

## Hardening pass (start-ride OTP attempt limit)
No UI changes. The limit is server-side only: the rider app never submits the start code, and pilots enter it in the pilot client. Screenshot verification re-run: PASS.
