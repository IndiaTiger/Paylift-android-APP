'use strict';
// Server-authoritative ride state machine.
//
// Every transition is a compare-and-set UPDATE (`WHERE status IN (allowed sources)`), executed in
// the same transaction as its money side effects, so concurrent cancel/complete, double requests
// or double completions resolve to exactly one winner and the loser gets 409 INVALID_TRANSITION.
const crypto = require('node:crypto');
const { ApiError, id, requireString, requireInt, haversineMeters } = require('./util');
const ledger = require('./ledger');
const fare = require('./fare');
const locations = require('./locations');

const S = {
  REQUESTED: 'REQUESTED', SEARCHING: 'SEARCHING', ASSIGNED: 'ASSIGNED', PILOT_ACCEPTED: 'PILOT_ACCEPTED',
  PILOT_ARRIVING: 'PILOT_ARRIVING', ARRIVED: 'ARRIVED', OTP_VERIFIED: 'OTP_VERIFIED', IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED', CANCELLED: 'CANCELLED', FAILED: 'FAILED', NO_PILOT_FOUND: 'NO_PILOT_FOUND',
};
const TERMINAL = new Set([S.COMPLETED, S.CANCELLED, S.FAILED, S.NO_PILOT_FOUND]);

/** Allowed transitions: target -> set of source states. */
const TRANSITIONS = {
  [S.SEARCHING]: [S.REQUESTED, S.ASSIGNED, S.PILOT_ACCEPTED, S.PILOT_ARRIVING, S.ARRIVED],
  [S.ASSIGNED]: [S.SEARCHING],
  [S.PILOT_ACCEPTED]: [S.ASSIGNED],
  [S.PILOT_ARRIVING]: [S.PILOT_ACCEPTED],
  [S.ARRIVED]: [S.PILOT_ACCEPTED, S.PILOT_ARRIVING],
  [S.OTP_VERIFIED]: [S.ARRIVED],
  [S.IN_PROGRESS]: [S.OTP_VERIFIED],
  [S.COMPLETED]: [S.IN_PROGRESS],
  [S.CANCELLED]: [S.REQUESTED, S.SEARCHING, S.ASSIGNED, S.PILOT_ACCEPTED, S.PILOT_ARRIVING, S.ARRIVED, S.OTP_VERIFIED],
  [S.NO_PILOT_FOUND]: [S.SEARCHING],
  [S.FAILED]: [S.REQUESTED, S.SEARCHING, S.ASSIGNED, S.PILOT_ACCEPTED, S.PILOT_ARRIVING, S.ARRIVED, S.OTP_VERIFIED, S.IN_PROGRESS],
};
// Cancelling from these states (rider already waited for an arrived pilot) may carry a fee.
const FEE_ELIGIBLE = new Set([S.ARRIVED, S.OTP_VERIFIED]);

function canTransition(from, to) {
  return (TRANSITIONS[to] || []).includes(from);
}

const DISPATCH_RADIUS_M = 10000;

function getRide(db, rideId) {
  return db.prepare('SELECT * FROM rides WHERE id = ?').get(rideId);
}

/** Loads a ride visible to `user` (its rider or its assigned pilot); others get 404. */
function getVisible(db, user, rideId) {
  const r = getRide(db, rideId);
  if (!r) throw new ApiError(404, 'NOT_FOUND', 'Ride not found');
  if (r.rider_id === user.id) return r;
  const pilot = pilotForUser(db, user);
  if (pilot && r.pilot_id === pilot.id) return r;
  throw new ApiError(404, 'NOT_FOUND', 'Ride not found');
}

function pilotForUser(db, user) {
  if (!user || user.role !== 'pilot') return null;
  return db.prepare('SELECT * FROM pilots WHERE user_id = ?').get(user.id) || null;
}

function requirePilotOf(db, user, ride) {
  const pilot = pilotForUser(db, user);
  if (!pilot || ride.pilot_id !== pilot.id) throw new ApiError(403, 'FORBIDDEN', 'Only the assigned pilot can do this');
  return pilot;
}

/**
 * Compare-and-set transition with side effects, all in one transaction.
 * Throws 409 INVALID_TRANSITION if the ride is not in an allowed source state.
 */
function transition(db, rideId, to, actor, extra = {}, sideEffects) {
  return db.tx(() => {
    const ride = getRide(db, rideId);
    if (!ride) throw new ApiError(404, 'NOT_FOUND', 'Ride not found');
    if (!canTransition(ride.status, to)) {
      throw new ApiError(409, 'INVALID_TRANSITION', `Cannot move ride from ${ride.status} to ${to}`, { status: ride.status });
    }
    const sets = ['status = ?', 'version = version + 1', 'updated_at = ?'];
    const args = [to, Date.now()];
    for (const [k, v] of Object.entries(extra)) {
      sets.push(`${k} = ?`);
      args.push(v);
    }
    const res = db.prepare(`UPDATE rides SET ${sets.join(', ')} WHERE id = ? AND status = ? AND version = ?`)
      .run(...args, rideId, ride.status, ride.version);
    if (res.changes !== 1) throw new ApiError(409, 'CONCURRENT_MODIFICATION', 'Ride changed concurrently, retry');
    db.prepare('INSERT INTO ride_events (ride_id, from_status, to_status, actor, created_at) VALUES (?, ?, ?, ?, ?)')
      .run(rideId, ride.status, to, actor, Date.now());
    if (sideEffects) sideEffects(ride);
    return getRide(db, rideId);
  });
}

function setPilotStatus(db, pilotId, status) {
  if (pilotId) db.prepare('UPDATE pilots SET status = ? WHERE id = ?').run(status, pilotId);
}

/** Settles money for a ride that ends without completion. */
function settleUnfinished(db, cfg, ride, { chargeFee }) {
  const fee = chargeFee ? Math.min(cfg.cancellationFeePaise, ride.fare_total_paise) : 0;
  if (fee > 0) {
    ledger.captureHold(db, ride.rider_id, ride.id, fee, { type: 'CANCELLATION_FEE', description: 'Cancellation fee' });
  }
  const rest = ride.fare_total_paise - fee;
  if (rest > 0) ledger.releaseHold(db, ride.rider_id, ride.id, rest, 'Fare hold released');
  db.prepare('UPDATE rides SET charged_paise = ? WHERE id = ?').run(fee, ride.id);
  setPilotStatus(db, ride.pilot_id, 'AVAILABLE');
}

// ---------------------------------------------------------------------------------------------
// Quotes and requests

async function quote(db, cfg, user, body) {
  const vehicleId = requireString(body.vehicleId, 'vehicleId', { max: 40 });
  if (!fare.VEHICLES[vehicleId]) throw new ApiError(400, 'UNKNOWN_VEHICLE', 'Unknown vehicle');
  const pickup = locations.validatePlace(body.pickup, 'pickup');
  const dropoff = locations.validatePlace(body.dropoff, 'dropoff');
  const route = await locations.route(cfg, { origin: pickup, destination: dropoff });
  const breakdown = fare.calculate(vehicleId, route.distanceMeters, route.durationMinutes);
  const q = {
    id: id('qt'), vehicleId, pickup, dropoff, distanceMeters: route.distanceMeters, durationMinutes: route.durationMinutes,
    route: { polyline: route.polyline, source: route.source }, fare: breakdown, expiresAt: Date.now() + cfg.quoteTtlSec * 1000,
  };
  db.prepare(`INSERT INTO quotes (id, rider_id, vehicle_id, pickup_json, dropoff_json, distance_m, duration_min, route_json, fare_json, total_paise, expires_at, created_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
    .run(q.id, user.id, vehicleId, JSON.stringify(pickup), JSON.stringify(dropoff), q.distanceMeters, q.durationMinutes,
      JSON.stringify(q.route), JSON.stringify(breakdown), breakdown.totalPaise, q.expiresAt, Date.now());
  return { quoteId: q.id, ...q, id: undefined };
}

/**
 * Creates a ride from a quote. The fare that is held (and later charged) is the quote's total,
 * i.e. exactly the amount the rider was shown. Idempotent on (rider, Idempotency-Key).
 */
function request(db, cfg, user, body, idempotencyKey) {
  const key = requireString(idempotencyKey, 'Idempotency-Key', { max: 100 });
  const quoteId = requireString(body.quoteId, 'quoteId', { max: 64 });
  const created = db.tx(() => {
    const existing = db.prepare('SELECT * FROM rides WHERE rider_id = ? AND idempotency_key = ?').get(user.id, key);
    if (existing) {
      if (existing.quote_id !== quoteId) throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency key reused for a different quote');
      return existing;
    }
    const q = db.prepare('SELECT * FROM quotes WHERE id = ? AND rider_id = ?').get(quoteId, user.id);
    if (!q) throw new ApiError(404, 'QUOTE_NOT_FOUND', 'Quote not found');
    if (q.expires_at < Date.now()) throw new ApiError(410, 'QUOTE_EXPIRED', 'Fare quote expired, please refresh');
    if (body.expectedTotalPaise !== undefined && body.expectedTotalPaise !== q.total_paise) {
      throw new ApiError(409, 'FARE_CHANGED', 'Displayed fare differs from the quoted fare');
    }
    if (db.prepare('SELECT 1 FROM rides WHERE quote_id = ?').get(quoteId)) throw new ApiError(409, 'QUOTE_USED', 'Quote already used');
    const active = db.prepare(`SELECT id FROM rides WHERE rider_id = ? AND status NOT IN ('COMPLETED','CANCELLED','FAILED','NO_PILOT_FOUND')`).get(user.id);
    if (active) throw new ApiError(409, 'ACTIVE_RIDE_EXISTS', 'You already have an active ride', { rideId: active.id });
    const now = Date.now();
    const rideId = id('ride');
    const otp = String(crypto.randomInt(0, 10000)).padStart(4, '0');
    db.prepare(`INSERT INTO rides (id, rider_id, quote_id, vehicle_id, status, pickup_json, dropoff_json, distance_m, duration_min,
                route_json, fare_json, fare_total_paise, start_otp, idempotency_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .run(rideId, user.id, quoteId, q.vehicle_id, q.pickup_json, q.dropoff_json, q.distance_m, q.duration_min,
        q.route_json, q.fare_json, q.total_paise, otp, key, now, now);
    db.prepare('INSERT INTO ride_events (ride_id, from_status, to_status, actor, created_at) VALUES (?, NULL, ?, ?, ?)')
      .run(rideId, S.REQUESTED, 'rider', now);
    // Reserve the fare atomically; insufficient funds aborts the whole transaction (no ride row).
    ledger.holdForRide(db, user.id, rideId, q.total_paise);
    return transition(db, rideId, S.SEARCHING, 'system', { search_started_at: now });
  });
  tryDispatch(db, created.id);
  return getRide(db, created.id);
}

// ---------------------------------------------------------------------------------------------
// Dispatch

function tryDispatch(db, rideId) {
  try {
    return db.tx(() => {
      const ride = getRide(db, rideId);
      if (!ride || ride.status !== S.SEARCHING) return null;
      const pickup = JSON.parse(ride.pickup_json);
      const candidates = db.prepare("SELECT * FROM pilots WHERE status = 'AVAILABLE'").all()
        .filter((p) => p.vehicle_ids.split(',').includes(ride.vehicle_id))
        .map((p) => ({ p, d: haversineMeters(pickup, p) }))
        .filter((c) => c.d <= DISPATCH_RADIUS_M)
        .sort((a, b) => a.d - b.d);
      if (!candidates.length) return null;
      return assign(db, rideId, candidates[0].p.id, 'dispatcher');
    });
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) return null;
    throw e;
  }
}

function assign(db, rideId, pilotId, actor) {
  return db.tx(() => {
    const pilot = db.prepare('SELECT * FROM pilots WHERE id = ?').get(pilotId);
    if (!pilot) throw new ApiError(404, 'NOT_FOUND', 'Pilot not found');
    const ride = getRide(db, rideId);
    if (!ride) throw new ApiError(404, 'NOT_FOUND', 'Ride not found');
    if (!pilot.vehicle_ids.split(',').includes(ride.vehicle_id)) throw new ApiError(409, 'VEHICLE_MISMATCH', 'Pilot does not operate this vehicle class');
    // Claim the pilot atomically: prevents one pilot being assigned to two rides.
    const claimed = db.prepare("UPDATE pilots SET status = 'BUSY' WHERE id = ? AND status = 'AVAILABLE'").run(pilotId);
    if (claimed.changes !== 1) throw new ApiError(409, 'PILOT_UNAVAILABLE', 'Pilot is not available');
    return transition(db, rideId, S.ASSIGNED, actor, { pilot_id: pilotId });
  });
}

/** Periodic housekeeping: dispatch searching rides, expire searches that took too long. */
function tick(db, cfg) {
  const searching = db.prepare("SELECT id, search_started_at FROM rides WHERE status = 'SEARCHING'").all();
  for (const r of searching) {
    if (r.search_started_at && Date.now() - r.search_started_at > cfg.searchTimeoutSec * 1000) {
      try {
        transition(db, r.id, S.NO_PILOT_FOUND, 'system', {}, (ride) => settleUnfinished(db, cfg, ride, { chargeFee: false }));
      } catch (e) {
        if (!(e instanceof ApiError)) throw e;
      }
    } else {
      tryDispatch(db, r.id);
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Rider / pilot actions

function cancel(db, cfg, user, rideId, body) {
  const ride = getVisible(db, user, rideId);
  const reason = typeof body.reason === 'string' ? body.reason.slice(0, 200) : null;
  if (ride.rider_id === user.id) {
    return transition(db, rideId, S.CANCELLED, 'rider', { cancel_reason: reason, cancelled_by: 'rider' },
      (before) => settleUnfinished(db, cfg, before, { chargeFee: FEE_ELIGIBLE.has(before.status) }));
  }
  // A pilot backing out before the trip starts returns the ride to the search pool; the rider's
  // hold stays in place and no money moves.
  requirePilotOf(db, user, ride);
  return transition(db, rideId, S.SEARCHING, 'pilot', { pilot_id: null, search_started_at: Date.now() },
    (before) => setPilotStatus(db, before.pilot_id, 'AVAILABLE'));
}

function pilotAction(db, user, rideId, to) {
  const ride = getVisible(db, user, rideId);
  requirePilotOf(db, user, ride);
  return transition(db, rideId, to, 'pilot');
}

/**
 * Pilot submits the rider's 4-digit start code.
 *
 * Brute-force protection: failed attempts are counted per ride in the database (persistent across
 * restarts) and incremented inside the same IMMEDIATE transaction as the check, so concurrent
 * guesses are serialized and each one is counted. After START_OTP_MAX_ATTEMPTS failures the ride is
 * locked for START_OTP_LOCK_SEC; after the lock expires the counter starts again. Errors never
 * reveal the code, how close a guess was, or the remaining attempts, and the code is never logged.
 */
function verifyStartOtp(db, cfg, user, rideId, body) {
  const ride = getVisible(db, user, rideId);
  requirePilotOf(db, user, ride);
  const otp = requireString(body.otp, 'otp', { min: 4, max: 4, pattern: /^\d{4}$/ });
  const outcome = db.tx(() => {
    const r = getRide(db, rideId);
    // State first: outside ARRIVED the endpoint is not a guessing oracle and nothing is counted.
    if (!canTransition(r.status, S.OTP_VERIFIED)) return { error: new ApiError(409, 'INVALID_TRANSITION', `Cannot verify OTP in ${r.status}`) };
    const now = Date.now();
    if (r.otp_locked_until && r.otp_locked_until > now) {
      return { error: new ApiError(429, 'OTP_LOCKED', 'Too many incorrect codes. Try again later.') };
    }
    if (r.otp_locked_until && r.otp_locked_until <= now) {
      db.prepare('UPDATE rides SET otp_failed_attempts = 0, otp_locked_until = NULL WHERE id = ?').run(rideId);
    }
    if (!crypto.timingSafeEqual(Buffer.from(otp), Buffer.from(r.start_otp))) {
      db.prepare(`UPDATE rides SET otp_failed_attempts = otp_failed_attempts + 1,
                  otp_locked_until = CASE WHEN otp_failed_attempts + 1 >= ? THEN ? ELSE NULL END WHERE id = ?`)
        .run(cfg.startOtpMaxAttempts, now + cfg.startOtpLockSec * 1000, rideId);
      db.prepare('INSERT INTO ride_events (ride_id, from_status, to_status, actor, created_at) VALUES (?, ?, ?, ?, ?)')
        .run(rideId, r.status, 'OTP_REJECTED', 'pilot', now);
      return { error: new ApiError(422, 'OTP_MISMATCH', 'Incorrect ride code') };
    }
    db.prepare('UPDATE rides SET otp_failed_attempts = 0, otp_locked_until = NULL WHERE id = ?').run(rideId);
    return { ride: transition(db, rideId, S.OTP_VERIFIED, 'pilot') };
  });
  // Raised after commit so the counted failure is persisted.
  if (outcome.error) throw outcome.error;
  return outcome.ride;
}

/** Completes the trip and charges exactly the quoted fare that was held. */
function complete(db, user, rideId, actor = 'pilot') {
  if (user) requirePilotOf(db, user, getVisible(db, user, rideId));
  return transition(db, rideId, S.COMPLETED, actor, { completed_at: Date.now() }, (before) => {
    ledger.captureHold(db, before.rider_id, before.id, before.fare_total_paise);
    db.prepare('UPDATE rides SET charged_paise = ? WHERE id = ?').run(before.fare_total_paise, before.id);
    db.prepare("UPDATE pilots SET status = 'AVAILABLE', total_trips = total_trips + 1 WHERE id = ?").run(before.pilot_id);
  });
}

function rate(db, user, rideId, body) {
  const ride = getVisible(db, user, rideId);
  if (ride.rider_id !== user.id) throw new ApiError(403, 'FORBIDDEN', 'Only the rider can rate');
  const stars = requireInt(body.stars, 'stars', { min: 1, max: 5 });
  const tags = Array.isArray(body.tags) ? body.tags.filter((t) => typeof t === 'string').map((t) => t.slice(0, 40)).slice(0, 10).join(', ') : '';
  const res = db.prepare("UPDATE rides SET rating_stars = ?, rating_tags = ?, updated_at = ? WHERE id = ? AND status = 'COMPLETED' AND rating_stars IS NULL")
    .run(stars, tags, Date.now(), rideId);
  if (res.changes !== 1) throw new ApiError(409, ride.status === S.COMPLETED ? 'ALREADY_RATED' : 'INVALID_TRANSITION', 'Ride cannot be rated');
  return getRide(db, rideId);
}

function postMessage(db, user, rideId, body) {
  const ride = getVisible(db, user, rideId);
  if (TERMINAL.has(ride.status)) throw new ApiError(409, 'RIDE_ENDED', 'Ride has ended');
  const text = requireString(body.body, 'body', { max: 500 });
  const m = { id: id('msg'), senderRole: ride.rider_id === user.id ? 'rider' : 'pilot', body: text, createdAt: Date.now() };
  db.prepare('INSERT INTO ride_messages (id, ride_id, sender_role, body, created_at) VALUES (?, ?, ?, ?, ?)').run(m.id, rideId, m.senderRole, text, m.createdAt);
  return m;
}

function listMessages(db, user, rideId) {
  getVisible(db, user, rideId);
  return db.prepare('SELECT id, sender_role senderRole, body, created_at createdAt FROM ride_messages WHERE ride_id = ? ORDER BY created_at').all(rideId);
}

// ---------------------------------------------------------------------------------------------
// DTOs

function progressFraction(ride, pilot) {
  if (ride.status === S.COMPLETED) return 1;
  if (ride.status !== S.IN_PROGRESS || !pilot) return 0;
  const poly = JSON.parse(ride.route_json).polyline || [];
  if (poly.length < 2) return 0;
  let best = 0;
  let bestD = Infinity;
  poly.forEach((pt, i) => {
    const d = haversineMeters(pt, pilot);
    if (d < bestD) { bestD = d; best = i; }
  });
  return best / (poly.length - 1);
}

function toDto(db, ride, viewer) {
  const pilot = ride.pilot_id ? db.prepare('SELECT * FROM pilots WHERE id = ?').get(ride.pilot_id) : null;
  const isRider = viewer && ride.rider_id === viewer.id;
  return {
    rideId: ride.id,
    status: ride.status,
    version: ride.version,
    vehicleId: ride.vehicle_id,
    pickup: JSON.parse(ride.pickup_json),
    dropoff: JSON.parse(ride.dropoff_json),
    distanceMeters: ride.distance_m,
    durationMinutes: ride.duration_min,
    route: JSON.parse(ride.route_json),
    fare: JSON.parse(ride.fare_json),
    fareTotalPaise: ride.fare_total_paise,
    chargedPaise: ride.charged_paise,
    startOtp: isRider && !TERMINAL.has(ride.status) ? ride.start_otp : null,
    pilot: pilot ? {
      pilotId: pilot.id, name: pilot.name, rating: pilot.rating, totalTrips: pilot.total_trips,
      vehicleModel: pilot.vehicle_model, vehicleColor: pilot.vehicle_color, licensePlate: pilot.license_plate,
      avatarInitials: pilot.avatar_initials, location: { lat: pilot.lat, lng: pilot.lng, updatedAt: pilot.location_updated_at },
    } : null,
    progressFraction: progressFraction(ride, pilot),
    cancelReason: ride.cancel_reason,
    cancelledBy: ride.cancelled_by,
    rating: ride.rating_stars ? { stars: ride.rating_stars, tags: ride.rating_tags } : null,
    createdAt: ride.created_at,
    updatedAt: ride.updated_at,
    completedAt: ride.completed_at,
  };
}

function listForRider(db, user, { limit = 50 } = {}) {
  return db.prepare('SELECT * FROM rides WHERE rider_id = ? ORDER BY created_at DESC LIMIT ?').all(user.id, limit);
}

function activeForUser(db, user) {
  const pilot = pilotForUser(db, user);
  const col = pilot ? 'pilot_id' : 'rider_id';
  const who = pilot ? pilot.id : user.id;
  return db.prepare(`SELECT * FROM rides WHERE ${col} = ? AND status NOT IN ('COMPLETED','CANCELLED','FAILED','NO_PILOT_FOUND') ORDER BY created_at DESC LIMIT 1`).get(who) || null;
}

module.exports = {
  S, TERMINAL, TRANSITIONS, canTransition, quote, request, transition, tryDispatch, assign, tick, cancel, pilotAction,
  verifyStartOtp, complete, rate, postMessage, listMessages, toDto, listForRider, activeForUser, getVisible, getRide,
  pilotForUser, settleUnfinished,
};
