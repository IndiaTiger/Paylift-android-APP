# PayLift API contract

Implemented by `backend/` (reference/test backend) and consumed by `app/src/main/java/com/example/api/PayLiftApi.kt`.

* JSON over HTTPS. All money is **integer paise** (`...Paise`), multipliers are integer basis points (`...Bp`, ×10000).
* Auth: `Authorization: Bearer <accessToken>` (HS256 JWT, 15 min). Refresh tokens rotate on every use; re-use of an old refresh token revokes the whole session family.
* Errors: `{"error": {"code": "SOME_CODE", "message": "human readable", "details": {...}?}}` with a meaningful HTTP status. `503 *_NOT_CONFIGURED` means a provider/credential is missing — clients must show "unavailable", never fake success.
* Idempotency: `POST /rides` and `POST /payments/orders` **require** an `Idempotency-Key` header. Replaying the same key returns the original result; reusing it with a different body returns `409 IDEMPOTENCY_CONFLICT`.
* Authorization: every resource is scoped to its owner. Another user's ride/payment returns `404` (existence is not leaked). Operator endpoints need `X-Admin-Token`.

## Auth
| Method | Path | Body | Response / notes |
|---|---|---|---|
| POST | `/auth/request-otp` | `{phone}` (E.164) | `{requestId, expiresInSec, devOtp?}` — `devOtp` only when `AUTH_PROVIDER=dev` (refused in production). 5 requests / 15 min / phone (`429`). |
| POST | `/auth/verify-otp` | `{phone, requestId, otp}` | `{accessToken, refreshToken, expiresInSec, isNewUser, user}`. OTP single-use, 5 min TTL, 5 attempts then `429 OTP_LOCKED`. New users get a wallet at **0**. |
| POST | `/auth/refresh` | `{refreshToken}` | New token pair. `401 REFRESH_TOKEN_REUSED` on replay. |
| POST | `/auth/logout` | `{refreshToken}` | Revokes the token. |
| DELETE | `/users/me` | – | `409 ACTIVE_RIDE_EXISTS` / `409 WALLET_NOT_EMPTY` if applicable; otherwise anonymises the user, revokes sessions, deletes addresses. Ledger and rides are retained (accounting), detached from PII. |

## Users
| GET | `/users/me` | | `UserDto` |
|---|---|---|---|
| PATCH | `/users/me` | any subset of `name, email, emergencyContactName, emergencyContactPhone, emergencyRelationship, autoDialSos` | Partial update — absent fields are untouched (no stale overwrite). |
| GET | `/users/me/addresses` | | `{addresses:[{addressId,label,name,subtitle,lat,lng}]}` |
| POST | `/users/me/addresses` | `{label,name,subtitle,lat,lng}` | `201` AddressDto (max 20) |
| DELETE | `/users/me/addresses/{id}` | | `404` if not owner |

## Wallet (read-only for clients)
| GET | `/wallet` | `{balancePaise, heldPaise, availablePaise, currency}` — `available = balance − held` |
|---|---|---|
| GET | `/wallet/transactions?limit&before` | Immutable ledger entries: `TOPUP_CREDIT, RIDE_HOLD, HOLD_RELEASE, RIDE_CAPTURE, CANCELLATION_FEE, RIDE_REFUND, TOPUP_REFUND` with `balanceAfterPaise, heldAfterPaise, reference` |

There is **no client endpoint that changes a balance.** Credits come only from verified payments; debits only from ride holds/captures.

## Payments
| Method | Path | Notes |
|---|---|---|
| POST | `/payments/orders` + `Idempotency-Key` | `{amountPaise}` validated server-side (`MIN_TOPUP_PAISE..MAX_TOPUP_PAISE`, integer). Returns `{paymentId, provider, providerOrderId, amountPaise, status:"PENDING", publicKey}`. |
| POST | `/payments/verify` | `{paymentId, outcome:"SUCCESS", providerPaymentId, signature}` → server verifies the provider signature, then credits the ledger (idempotent). `{outcome:"FAILED"|"CANCELLED"}` can only move `PENDING` to a terminal state — never credits. |
| POST | `/payments/webhook` | Raw body signed by the provider (`X-PayLift-Signature` for the test gateway). Deduplicated by `event.id`; captured amount must equal the order amount (`AMOUNT_MISMATCH` fails the payment). Credits through the same idempotent path as verify. |
| GET | `/payments/{paymentId}` | Owner only. |
| POST | `/payments/{paymentId}/refund` | **Operator only.** `{refundId, amountPaise?}`; bounded by the un-refunded amount and by available funds; idempotent per `refundId`. |
| POST | `/payments/test/checkout` | **Test gateway only** (`PAYMENT_PROVIDER=test`, non-production). Simulates the hosted checkout and sends the signed webhook. |

States: `PENDING → SUCCESS | FAILED | CANCELLED`, `SUCCESS → REFUNDED` (full refund). Uniqueness: `provider_order_id`, `provider_payment_id`, `(user, idempotency_key)`, ledger `reference = topup:<paymentId>`.

## Locations
| POST | `/locations/geocode` | `{query}` → `{results:[{name,subtitle,lat,lng,category,source}]}` |
|---|---|---|
| POST | `/locations/reverse-geocode` | `{lat,lng}` → place |
| POST | `/routes` | `{origin:{lat,lng}, destination:{lat,lng}}` → `{distanceMeters, durationMinutes, polyline, source}`; `422 PICKUP_EQUALS_DROPOFF` under 200 m |

`source` is `dev-static` / `dev-approximation` for the development adapter and `google` for the Google adapter.

## Rides
| Method | Path | Actor | Transition |
|---|---|---|---|
| POST | `/rides/quote` | rider | `{vehicleId, pickup, dropoff}` → authoritative fare (paise) + route, valid `QUOTE_TTL_SEC` |
| POST | `/rides` + `Idempotency-Key` | rider | `{quoteId, expectedTotalPaise}` → holds exactly the quoted total, `REQUESTED → SEARCHING` (auto-dispatch may yield `ASSIGNED`). `409 FARE_CHANGED`, `410 QUOTE_EXPIRED`, `409 INSUFFICIENT_FUNDS` (no ride created), `409 ACTIVE_RIDE_EXISTS`. |
| GET | `/rides`, `/rides/active`, `/rides/{id}` | rider / assigned pilot | Recovery after restart uses `/rides/active`. `startOtp` is visible to the rider only. |
| POST | `/rides/{id}/cancel` | rider | → `CANCELLED`; releases the hold (fee `CANCELLATION_FEE_PAISE` only from `ARRIVED`/`OTP_VERIFIED`). Pilot cancel returns the ride to `SEARCHING`. |
| POST | `/rides/{id}/assign` | operator | `SEARCHING → ASSIGNED` (pilot claimed atomically) |
| POST | `/rides/{id}/accept` | pilot | `ASSIGNED → PILOT_ACCEPTED` |
| POST | `/rides/{id}/arriving` | pilot | `PILOT_ACCEPTED → PILOT_ARRIVING` |
| POST | `/rides/{id}/arrived` | pilot | `→ ARRIVED` |
| POST | `/rides/{id}/verify-otp` | pilot | `{otp}` `ARRIVED → OTP_VERIFIED`. State is checked first (`409`, not counted). Wrong code → `422 OTP_MISMATCH` (generic, no remaining-attempt count). After `START_OTP_MAX_ATTEMPTS` (default 5) failures → `429 OTP_LOCKED` for `START_OTP_LOCK_SEC` (default 300 s), even for the correct code; then the budget resets. The counter is stored on the ride row and updated in the same transaction as the check. |
| POST | `/rides/{id}/start` | pilot | `OTP_VERIFIED → IN_PROGRESS` |
| POST | `/rides/{id}/complete` | pilot | `IN_PROGRESS → COMPLETED`; captures exactly the held fare |
| POST | `/rides/{id}/fail` | operator | → `FAILED`, releases the hold |
| POST | `/rides/{id}/rating` | rider | `{stars 1..5, tags[]}` once, `COMPLETED` only |
| GET/POST | `/rides/{id}/messages` | participants | Relayed chat |
| POST | `/rides/{id}/call` | participants | `503 CALL_PROVIDER_NOT_CONFIGURED` until a telephony provider exists |

Search timeout (`SEARCH_TIMEOUT_SEC`) → `NO_PILOT_FOUND` and the hold is released. Every transition is a compare-and-set in one DB transaction with its money side effect; losers get `409 INVALID_TRANSITION`. The full table lives in `backend/src/rides.js` (`TRANSITIONS`) and is mirrored — and test-checked — in `domain/RideLifecycle.kt`.

## Pilots
| GET | `/pilots/nearby?lat&lng&vehicleId` | Available pilots (coarse location, distance, ETA). No identities. |
|---|---|---|
| POST | `/pilots/me/location` | `{lat,lng}` pilot only |
| POST | `/pilots/me/availability` | `{status: AVAILABLE|OFFLINE}`; `409 PILOT_BUSY` during a ride |

## Safety
| POST | `/safety/sos` | `{lat?, lng?, rideId?}` → records the event and returns `503 SOS_NOT_CONFIGURED` until an emergency integration exists. The app then tells the user nothing was sent and offers to dial 112. |
|---|---|---|

## Config
`GET /config` (public) — which providers are configured, `sosAvailable`, `maskedCallAvailable`, `testMode`.
