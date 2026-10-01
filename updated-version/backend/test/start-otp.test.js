'use strict';
// Start-ride OTP brute-force protection.
const test = require('node:test');
const assert = require('node:assert/strict');
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');
const { startApp } = require('./helpers');

const MAX = 5;
let t;
test.before(async () => { t = await startApp({ env: { START_OTP_MAX_ATTEMPTS: String(MAX), START_OTP_LOCK_SEC: '300' } }); });
test.after(() => t.close());

let seq = 0;
function reset(app) {
  const db = app.db;
  const rides = require('../src/rides');
  for (const r of db.prepare("SELECT id FROM rides WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED','NO_PILOT_FOUND')").all()) {
    rides.transition(db, r.id, 'FAILED', 'test', {}, (b) => rides.settleUnfinished(db, app.cfg, b, { chargeFee: false }));
  }
  db.exec("UPDATE pilots SET status = 'AVAILABLE'");
}

/** Rider with a ride whose pilot has ARRIVED; returns tokens, ride id and the correct code. */
async function arrivedRide(h = t) {
  reset(h.app);
  const s = await h.login(`+9193000${String(seq++).padStart(5, '0')}`);
  await h.topUp(s.accessToken, 100000);
  const pilot = await h.pilotLogin(0);
  const { ride } = await h.requestRide(s.accessToken);
  assert.equal(ride.status, 201, JSON.stringify(ride.body));
  for (const step of ['accept', 'arrived']) {
    const r = await h.call('POST', `/rides/${ride.body.rideId}/${step}`, { token: pilot.accessToken, body: {} });
    assert.equal(r.status, 200);
  }
  const otp = ride.body.startOtp;
  const wrong = (n) => String((Number(otp) + 1 + n) % 10000).padStart(4, '0');
  return { rider: s, pilot, rideId: ride.body.rideId, otp, wrong };
}

const verify = (h, pilot, rideId, otp) => h.call('POST', `/rides/${rideId}/verify-otp`, { token: pilot.accessToken, body: { otp } });
const attempts = (h, rideId) => h.app.db.prepare('SELECT otp_failed_attempts a, otp_locked_until l FROM rides WHERE id = ?').get(rideId);

test('successful OTP verifies and resets the counter', async () => {
  const r = await arrivedRide();
  await verify(t, r.pilot, r.rideId, r.wrong(0));
  const ok = await verify(t, r.pilot, r.rideId, r.otp);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.status, 'OTP_VERIFIED');
  assert.equal(attempts(t, r.rideId).a, 0);
});

test('wrong OTP is rejected without revealing details', async () => {
  const r = await arrivedRide();
  const res = await verify(t, r.pilot, r.rideId, r.wrong(0));
  assert.equal(res.status, 422);
  assert.deepEqual(res.body, { error: { code: 'OTP_MISMATCH', message: 'Incorrect ride code' } });
  const text = JSON.stringify(res.body);
  assert.ok(!text.includes(r.otp), 'response must not contain the code');
  assert.ok(!/remaining|attempt/i.test(text), 'response must not reveal the attempt budget');
  assert.equal(attempts(t, r.rideId).a, 1);
});

test('repeated wrong OTPs are each counted', async () => {
  const r = await arrivedRide();
  for (let i = 0; i < MAX - 1; i++) assert.equal((await verify(t, r.pilot, r.rideId, r.wrong(i))).status, 422);
  assert.equal(attempts(t, r.rideId).a, MAX - 1);
  assert.equal(attempts(t, r.rideId).l, null);
  assert.equal((await verify(t, r.pilot, r.rideId, r.otp)).status, 200, 'still unlocked below the limit');
});

test('maximum attempts lock the ride - even the correct code is refused', async () => {
  const r = await arrivedRide();
  for (let i = 0; i < MAX; i++) assert.equal((await verify(t, r.pilot, r.rideId, r.wrong(i))).status, 422);
  assert.ok(attempts(t, r.rideId).l > Date.now());
  const locked = await verify(t, r.pilot, r.rideId, r.otp);
  assert.equal(locked.status, 429);
  assert.deepEqual(locked.body, { error: { code: 'OTP_LOCKED', message: 'Too many incorrect codes. Try again later.' } });
  assert.equal((await t.call('GET', `/rides/${r.rideId}`, { token: r.rider.accessToken })).body.status, 'ARRIVED');
  // Locked attempts are not counted further (no unbounded growth).
  assert.equal(attempts(t, r.rideId).a, MAX);
  // Rider can still cancel a locked ride.
  assert.equal((await t.call('POST', `/rides/${r.rideId}/cancel`, { token: r.rider.accessToken, body: {} })).status, 200);
});

test('concurrent guesses are all counted and cannot exceed the limit', async () => {
  const r = await arrivedRide();
  const guesses = Array.from({ length: 20 }, (_, i) => verify(t, r.pilot, r.rideId, r.wrong(i)));
  const results = await Promise.all(guesses);
  const mismatches = results.filter((x) => x.status === 422).length;
  const lockedOut = results.filter((x) => x.status === 429).length;
  assert.equal(mismatches, MAX, 'exactly MAX guesses evaluated');
  assert.equal(lockedOut, 20 - MAX);
  assert.equal(attempts(t, r.rideId).a, MAX);
  // Concurrent correct + wrong: at most one transition happens.
  const r2 = await arrivedRide();
  const mixed = await Promise.all([verify(t, r2.pilot, r2.rideId, r2.otp), verify(t, r2.pilot, r2.rideId, r2.otp), verify(t, r2.pilot, r2.rideId, r2.wrong(0))]);
  assert.equal(mixed.filter((x) => x.status === 200).length, 1);
});

test('retry after lockout expiry gets a fresh budget', async () => {
  const r = await arrivedRide();
  for (let i = 0; i < MAX; i++) await verify(t, r.pilot, r.rideId, r.wrong(i));
  assert.equal((await verify(t, r.pilot, r.rideId, r.otp)).status, 429);
  // Simulate the lock window passing.
  t.app.db.prepare('UPDATE rides SET otp_locked_until = ? WHERE id = ?').run(Date.now() - 1, r.rideId);
  assert.equal((await verify(t, r.pilot, r.rideId, r.wrong(0))).status, 422);
  assert.equal(attempts(t, r.rideId).a, 1, 'counter restarted after the lock expired');
  assert.equal((await verify(t, r.pilot, r.rideId, r.otp)).status, 200);
});

test('OTP checks outside ARRIVED are not evaluated or counted', async () => {
  const r = await arrivedRide();
  await verify(t, r.pilot, r.rideId, r.otp); // -> OTP_VERIFIED
  const again = await verify(t, r.pilot, r.rideId, r.wrong(0));
  assert.equal(again.status, 409);
  assert.equal(attempts(t, r.rideId).a, 0);
});

test('the counter is persistent across a server restart', async () => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'paylift-otp-')), 'otp.db');
  const { createApp } = require('../src/server');
  const env = { NODE_ENV: 'test', AUTH_PROVIDER: 'dev', PAYMENT_PROVIDER: 'test', MAPS_PROVIDER: 'dev', START_OTP_MAX_ATTEMPTS: String(MAX), JWT_SECRET: 'restart-test-jwt', OTP_PEPPER: 'restart-test-pepper' };
  const app1 = createApp({ env, dbFile: file, quiet: true, seedDevPilots: true });
  await app1.listen(0, '127.0.0.1');
  const h1 = await wrap(app1);
  const r = await arrivedRide(h1);
  for (let i = 0; i < MAX - 1; i++) await verify(h1, r.pilot, r.rideId, r.wrong(i));
  await app1.close();
  const app2 = createApp({ env, dbFile: file, quiet: true, seedDevPilots: true });
  await app2.listen(0, '127.0.0.1');
  const h2 = await wrap(app2);
  try {
    assert.equal(attempts(h2, r.rideId).a, MAX - 1);
    await verify(h2, r.pilot, r.rideId, r.wrong(9));
    assert.equal((await verify(h2, r.pilot, r.rideId, r.otp)).status, 429, 'locked after restart');
  } finally {
    await app2.close();
  }
});

// Minimal client around an already-listening app (same JWT secret => tokens survive restart).
async function wrap(app) {
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const call = async (method, p, { body, token, headers = {} } = {}) => {
    const h = { ...headers };
    if (body !== undefined) h['Content-Type'] = 'application/json';
    if (token) h.Authorization = `Bearer ${token}`;
    const res = await fetch(base + p, { method, headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
    return { status: res.status, body: await res.json() };
  };
  const login = async (phone) => {
    const o = await call('POST', '/auth/request-otp', { body: { phone } });
    return (await call('POST', '/auth/verify-otp', { body: { phone, requestId: o.body.requestId, otp: o.body.devOtp } })).body;
  };
  const sessions = {};
  return {
    app, call, login,
    pilotLogin: async (i) => (sessions[i] ??= await login(require('../src/pilots').DEV_PILOTS[i].phone)),
    topUp: async (token, amountPaise) => {
      const o = await call('POST', '/payments/orders', { token, body: { amountPaise }, headers: { 'Idempotency-Key': `k${Math.random()}` } });
      const c = await call('POST', '/payments/test/checkout', { token, body: { paymentId: o.body.paymentId, outcome: 'SUCCESS', deliverWebhook: false } });
      return call('POST', '/payments/verify', { token, body: { paymentId: o.body.paymentId, providerPaymentId: c.body.providerPaymentId, signature: c.body.signature } });
    },
    requestRide: async (token) => {
      const q = await call('POST', '/rides/quote', { token, body: { vehicleId: 'cab_mini', pickup: { name: 'A', lat: 30.3255, lng: 78.0436 }, dropoff: { name: 'B', lat: 30.358, lng: 78.072 } } });
      return { ride: await call('POST', '/rides', { token, body: { quoteId: q.body.quoteId }, headers: { 'Idempotency-Key': `r${Math.random()}` } }) };
    },
  };
}
