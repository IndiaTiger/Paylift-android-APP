'use strict';
// Ride lifecycle, state-machine guards, money side effects and concurrency.
const test = require('node:test');
const assert = require('node:assert/strict');
const { startApp } = require('./helpers');
const rides = require('../src/rides');
const ledger = require('../src/ledger');

let t;
test.before(async () => { t = await startApp({ env: { CANCELLATION_FEE_PAISE: '2500' } }); });
test.after(() => t.close());

let phoneSeq = 100;
async function funded(amount = 100000) {
  const s = await t.login(`+9197000${String(phoneSeq++).padStart(5, '0')}`);
  if (amount) await t.topUp(s.accessToken, amount);
  return s;
}
const wallet = async (token) => (await t.call('GET', '/wallet', { token })).body;
const userIdOf = (s) => s.user.userId;

// End rides left active by previous tests (through the guarded transition, so holds are
// released) so dispatch to pilot 0 is deterministic.
function resetPilots() {
  const db = t.app.db;
  for (const r of db.prepare("SELECT id FROM rides WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED','NO_PILOT_FOUND')").all()) {
    rides.transition(db, r.id, 'FAILED', 'test', {}, (before) => rides.settleUnfinished(db, t.app.cfg, before, { chargeFee: false }));
  }
  db.exec("UPDATE pilots SET status = 'AVAILABLE'");
}

async function drive(pilotToken, rideId, otp) {
  const steps = [['accept'], ['arriving'], ['arrived'], ['verify-otp', { otp }], ['start'], ['complete']];
  let last;
  for (const [step, body] of steps) {
    last = await t.call('POST', `/rides/${rideId}/${step}`, { token: pilotToken, body: body || {} });
    assert.equal(last.status, 200, `${step}: ${JSON.stringify(last.body)}`);
  }
  return last;
}

test('transition table: terminal states have no exits', () => {
  for (const term of rides.TERMINAL) {
    for (const to of Object.keys(rides.TRANSITIONS)) assert.equal(rides.canTransition(term, to), false, `${term} -> ${to}`);
  }
  assert.equal(rides.canTransition('IN_PROGRESS', 'CANCELLED'), false, 'no cancel once the trip is in progress');
  assert.equal(rides.canTransition('SEARCHING', 'COMPLETED'), false);
});

test('full lifecycle: displayed fare == held == charged, ledger reconciles', async () => {
  resetPilots();
  const s = await funded();
  const pilot = await t.pilotLogin(0);
  const { quote, ride } = await t.requestRide(s.accessToken);
  assert.equal(ride.status, 201, JSON.stringify(ride.body));
  assert.equal(ride.body.status, 'ASSIGNED', 'auto-dispatched to the nearest matching pilot');
  assert.equal(ride.body.fareTotalPaise, quote.body.fare.totalPaise);
  let w = await wallet(s.accessToken);
  assert.equal(w.heldPaise, quote.body.fare.totalPaise);
  const done = await drive(pilot.accessToken, ride.body.rideId, ride.body.startOtp);
  assert.equal(done.body.status, 'COMPLETED');
  assert.equal(done.body.chargedPaise, quote.body.fare.totalPaise);
  w = await wallet(s.accessToken);
  assert.equal(w.heldPaise, 0);
  assert.equal(w.balancePaise, 100000 - quote.body.fare.totalPaise);
  assert.ok(ledger.reconcile(t.app.db, userIdOf(s)).ok);
  // Double completion is rejected and does not charge again.
  const again = await t.call('POST', `/rides/${ride.body.rideId}/complete`, { token: pilot.accessToken });
  assert.equal(again.status, 409);
  assert.equal((await wallet(s.accessToken)).balancePaise, 100000 - quote.body.fare.totalPaise);
  // Cancellation after completion is rejected.
  assert.equal((await t.call('POST', `/rides/${ride.body.rideId}/cancel`, { token: s.accessToken, body: {} })).status, 409);
  // Rating once.
  assert.equal((await t.call('POST', `/rides/${ride.body.rideId}/rating`, { token: s.accessToken, body: { stars: 5, tags: ['Polite Pilot'] } })).status, 200);
  assert.equal((await t.call('POST', `/rides/${ride.body.rideId}/rating`, { token: s.accessToken, body: { stars: 1 } })).status, 409);
});

test('insufficient funds: no ride is created and nothing is held', async () => {
  const s = await funded(0);
  const { ride } = await t.requestRide(s.accessToken);
  assert.equal(ride.status, 409);
  assert.equal(ride.body.error.code, 'INSUFFICIENT_FUNDS');
  assert.equal((await t.call('GET', '/rides/active', { token: s.accessToken })).body.ride, null);
});

test('double-tap request: same idempotency key returns the same ride; different key is blocked', async () => {
  resetPilots();
  const s = await funded();
  const q = await t.quote(s.accessToken);
  const body = { quoteId: q.body.quoteId };
  const rs = await Promise.all([1, 2, 3].map(() => t.call('POST', '/rides', { token: s.accessToken, body, headers: { 'Idempotency-Key': 'tap' } })));
  const ids = new Set(rs.filter((r) => r.status === 201).map((r) => r.body.rideId));
  assert.equal(ids.size, 1);
  assert.equal((await wallet(s.accessToken)).heldPaise, q.body.fare.totalPaise, 'held once');
  const q2 = await t.quote(s.accessToken);
  const second = await t.call('POST', '/rides', { token: s.accessToken, body: { quoteId: q2.body.quoteId }, headers: { 'Idempotency-Key': 'other' } });
  assert.equal(second.status, 409);
  assert.equal(second.body.error.code, 'ACTIVE_RIDE_EXISTS');
  await t.call('POST', `/rides/${[...ids][0]}/cancel`, { token: s.accessToken, body: {} });
});

test('cancel before pickup releases the full hold (no refund entry, no money created)', async () => {
  resetPilots();
  const s = await funded();
  const { ride } = await t.requestRide(s.accessToken);
  const c = await t.call('POST', `/rides/${ride.body.rideId}/cancel`, { token: s.accessToken, body: { reason: 'changed plans' } });
  assert.equal(c.body.status, 'CANCELLED');
  assert.deepEqual(await wallet(s.accessToken), { balancePaise: 100000, heldPaise: 0, availablePaise: 100000, currency: 'INR' });
  const types = (await t.call('GET', '/wallet/transactions', { token: s.accessToken })).body.transactions.map((e) => e.type);
  assert.deepEqual(types.sort(), ['HOLD_RELEASE', 'RIDE_HOLD', 'TOPUP_CREDIT']);
  assert.equal((await t.call('POST', `/rides/${ride.body.rideId}/cancel`, { token: s.accessToken, body: {} })).status, 409, 'double cancel');
});

test('cancel after pilot arrived charges the configured fee and releases the rest', async () => {
  resetPilots();
  const s = await funded();
  const pilot = await t.pilotLogin(0);
  const { ride } = await t.requestRide(s.accessToken);
  for (const step of ['accept', 'arrived']) await t.call('POST', `/rides/${ride.body.rideId}/${step}`, { token: pilot.accessToken, body: {} });
  const c = await t.call('POST', `/rides/${ride.body.rideId}/cancel`, { token: s.accessToken, body: {} });
  assert.equal(c.body.chargedPaise, 2500);
  const w = await wallet(s.accessToken);
  assert.equal(w.balancePaise, 100000 - 2500);
  assert.equal(w.heldPaise, 0);
  assert.ok(ledger.reconcile(t.app.db, userIdOf(s)).ok);
});

test('race: cancel vs complete -> exactly one wins, money consistent', async () => {
  for (let i = 0; i < 5; i++) {
    resetPilots();
    const s = await funded();
    const pilot = await t.pilotLogin(0);
    const { ride } = await t.requestRide(s.accessToken);
    const id = ride.body.rideId;
    for (const [step, body] of [['accept'], ['arrived'], ['verify-otp', { otp: ride.body.startOtp }]]) {
      await t.call('POST', `/rides/${id}/${step}`, { token: pilot.accessToken, body: body || {} });
    }
    // From OTP_VERIFIED: start+complete races rider cancel.
    const [cancel, startRes] = await Promise.all([
      t.call('POST', `/rides/${id}/cancel`, { token: s.accessToken, body: {} }),
      t.call('POST', `/rides/${id}/start`, { token: pilot.accessToken }).then(async (r) => (r.status === 200 ? t.call('POST', `/rides/${id}/complete`, { token: pilot.accessToken }) : r)),
    ]);
    const final = (await t.call('GET', `/rides/${id}`, { token: s.accessToken })).body;
    assert.ok(['CANCELLED', 'COMPLETED'].includes(final.status));
    assert.ok((cancel.status === 200) !== (startRes.status === 200 && final.status === 'COMPLETED') || final.status === 'CANCELLED');
    const w = await wallet(s.accessToken);
    assert.equal(w.heldPaise, 0);
    assert.equal(w.balancePaise, 100000 - final.chargedPaise);
    assert.ok(ledger.reconcile(t.app.db, userIdOf(s)).ok);
  }
});

test('race: top-up + ride hold + concurrent top-ups keep the ledger consistent', async () => {
  resetPilots();
  const s = await funded(20000);
  const ops = [
    t.topUp(s.accessToken, 10000, 'c1'), t.topUp(s.accessToken, 10000, 'c2'), t.topUp(s.accessToken, 10000, 'c1'),
    t.requestRide(s.accessToken, { key: 'race-ride' }),
  ];
  await Promise.all(ops);
  const w = await wallet(s.accessToken);
  assert.equal(w.balancePaise, 40000, 'two distinct top-ups (c1 deduplicated)');
  assert.ok(ledger.reconcile(t.app.db, userIdOf(s)).ok);
});

test('ride hold cannot exceed available balance under concurrent requests from two quotes', async () => {
  resetPilots();
  const s = await funded(30000); // enough for exactly one mini ride (~Rs 194)
  const [a, b] = await Promise.all([t.requestRide(s.accessToken, { key: 'x1' }), t.requestRide(s.accessToken, { key: 'x2' })]);
  assert.equal([a, b].filter((r) => r.ride.status === 201).length, 1);
  const w = await wallet(s.accessToken);
  assert.ok(w.heldPaise <= w.balancePaise);
});

test('authorization: other users cannot see or act on a ride', async () => {
  resetPilots();
  const s = await funded();
  const other = await funded(0);
  const otherPilot = await t.pilotLogin(2); // SUV pilot, not assigned
  const { ride } = await t.requestRide(s.accessToken);
  const id = ride.body.rideId;
  assert.equal((await t.call('GET', `/rides/${id}`, { token: other.accessToken })).status, 404);
  assert.equal((await t.call('POST', `/rides/${id}/cancel`, { token: other.accessToken, body: {} })).status, 404);
  assert.equal((await t.call('POST', `/rides/${id}/complete`, { token: otherPilot.accessToken })).status, 404);
  assert.equal((await t.call('POST', `/rides/${id}/accept`, { token: s.accessToken })).status, 403, 'rider cannot act as pilot');
  assert.equal((await t.call('POST', `/rides/${id}/assign`, { token: s.accessToken, body: { pilotId: 'x' } })).status, 403, 'assign is operator-only');
  assert.equal((await t.call('GET', `/rides/${id}`)).status, 401);
  // Pilot view never exposes the rider's start OTP.
  const pilot = await t.pilotLogin(0);
  assert.equal((await t.call('GET', `/rides/${id}`, { token: pilot.accessToken })).body.startOtp, null);
  assert.equal((await t.call('POST', `/rides/${id}/verify-otp`, { token: pilot.accessToken, body: { otp: '0000' === ride.body.startOtp ? '1111' : '0000' } })).status, 409, 'wrong state for OTP');
  await t.call('POST', `/rides/${id}/cancel`, { token: s.accessToken, body: {} });
});

test('wrong ride OTP is rejected', async () => {
  resetPilots();
  const s = await funded();
  const pilot = await t.pilotLogin(0);
  const { ride } = await t.requestRide(s.accessToken);
  for (const step of ['accept', 'arrived']) await t.call('POST', `/rides/${ride.body.rideId}/${step}`, { token: pilot.accessToken, body: {} });
  const wrong = ride.body.startOtp === '0000' ? '1111' : '0000';
  const r = await t.call('POST', `/rides/${ride.body.rideId}/verify-otp`, { token: pilot.accessToken, body: { otp: wrong } });
  assert.equal(r.status, 422);
  assert.equal((await t.call('POST', `/rides/${ride.body.rideId}/start`, { token: pilot.accessToken })).status, 409, 'cannot start without OTP');
  await t.call('POST', `/rides/${ride.body.rideId}/cancel`, { token: s.accessToken, body: {} });
});

test('no pilot found: search timeout releases the hold', async () => {
  const u = await startApp({ env: { SEARCH_TIMEOUT_SEC: '0' }, seedDevPilots: false });
  try {
    const s = await u.login('+919600000001');
    await u.topUp(s.accessToken, 50000);
    const { ride } = await u.requestRide(s.accessToken);
    assert.equal(ride.body.status, 'SEARCHING');
    await new Promise((r) => setTimeout(r, 5));
    rides.tick(u.app.db, u.app.cfg);
    const r = await u.call('GET', `/rides/${ride.body.rideId}`, { token: s.accessToken });
    assert.equal(r.body.status, 'NO_PILOT_FOUND');
    assert.deepEqual((await u.call('GET', '/wallet', { token: s.accessToken })).body.heldPaise, 0);
  } finally {
    await u.close();
  }
});

test('pickup equal to drop-off is rejected; stale / tampered fare rejected', async () => {
  const s = await funded();
  const same = await t.call('POST', '/rides/quote', { token: s.accessToken, body: { vehicleId: 'cab_mini', pickup: t.CLOCK_TOWER, dropoff: t.CLOCK_TOWER } });
  assert.equal(same.status, 422);
  assert.equal(same.body.error.code, 'PICKUP_EQUALS_DROPOFF');
  const q = await t.quote(s.accessToken);
  const tampered = await t.call('POST', '/rides', { token: s.accessToken, body: { quoteId: q.body.quoteId, expectedTotalPaise: 1 }, headers: { 'Idempotency-Key': 'tamper' } });
  assert.equal(tampered.status, 409);
  assert.equal(tampered.body.error.code, 'FARE_CHANGED');
});

test('refund requires an original charge and cannot exceed it', async () => {
  resetPilots();
  const s = await funded();
  const pilot = await t.pilotLogin(0);
  const { ride } = await t.requestRide(s.accessToken);
  const db = t.app.db;
  assert.throws(() => ledger.refundRide(db, userIdOf(s), ride.body.rideId, 'rf-early', 100), /original ride charge/);
  const done = await drive(pilot.accessToken, ride.body.rideId, ride.body.startOtp);
  const charged = done.body.chargedPaise;
  ledger.refundRide(db, userIdOf(s), ride.body.rideId, 'rf-1', charged - 100);
  assert.throws(() => ledger.refundRide(db, userIdOf(s), ride.body.rideId, 'rf-2', 101), /exceeds/);
  assert.equal(ledger.refundRide(db, userIdOf(s), ride.body.rideId, 'rf-1', charged - 100).replayed, true);
  assert.throws(() => ledger.refundRide(db, userIdOf(s), 'ride_does_not_exist', 'rf-3', 1), /original ride charge/);
  assert.ok(ledger.reconcile(db, userIdOf(s)).ok);
});

test('ledger rows are immutable and wallet cannot go negative at the DB level', async () => {
  const s = await funded(1000);
  const db = t.app.db;
  assert.throws(() => db.exec("UPDATE ledger_entries SET amount_paise = 999999"), /immutable/);
  assert.throws(() => db.exec('DELETE FROM ledger_entries'), /immutable/);
  assert.throws(() => db.prepare('UPDATE wallets SET balance_paise = -1 WHERE user_id = ?').run(userIdOf(s)), /CHECK/);
});

test('dev pilot simulator drives a ride to completion through guarded transitions', async () => {
  const u = await startApp({ env: { DEV_PILOT_SIMULATOR: 'true', DEV_SIM_STEP_MS: '100000' } });
  try {
    const s = await u.login('+919600000002');
    await u.topUp(s.accessToken, 100000);
    const { ride } = await u.requestRide(s.accessToken);
    const pilots = require('../src/pilots');
    for (let i = 0; i < 20; i++) pilots.simulatorStep(u.app.db);
    const r = await u.call('GET', `/rides/${ride.body.rideId}`, { token: s.accessToken });
    assert.equal(r.body.status, 'COMPLETED');
    assert.equal(r.body.chargedPaise, ride.body.fareTotalPaise);
    const events = u.app.db.prepare('SELECT to_status FROM ride_events WHERE ride_id = ? ORDER BY id').all(ride.body.rideId).map((e) => e.to_status);
    assert.deepEqual(events, ['REQUESTED', 'SEARCHING', 'ASSIGNED', 'PILOT_ACCEPTED', 'PILOT_ARRIVING', 'ARRIVED', 'OTP_VERIFIED', 'IN_PROGRESS', 'COMPLETED']);
  } finally {
    await u.close();
  }
});
