'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { startApp } = require('./helpers');
const auth = require('../src/auth');

let t;
test.before(async () => { t = await startApp(); });
test.after(() => t.close());

test('OTP login issues tokens; wrong OTP counts attempts and locks', async () => {
  const phone = '+919500000001';
  const r = await t.call('POST', '/auth/request-otp', { body: { phone } });
  assert.equal(r.status, 200);
  const wrong = r.body.devOtp === '000000' ? '111111' : '000000';
  for (let i = 0; i < 5; i++) {
    const v = await t.call('POST', '/auth/verify-otp', { body: { phone, requestId: r.body.requestId, otp: wrong } });
    assert.equal(v.status, 401);
  }
  const locked = await t.call('POST', '/auth/verify-otp', { body: { phone, requestId: r.body.requestId, otp: r.body.devOtp } });
  assert.equal(locked.status, 429);
  const s = await t.login(phone);
  assert.ok(s.accessToken && s.refreshToken);
  assert.equal(s.user.phone, phone);
});

test('OTP is single use and phone must be E.164', async () => {
  const phone = '+919500000002';
  const r = await t.call('POST', '/auth/request-otp', { body: { phone } });
  const body = { phone, requestId: r.body.requestId, otp: r.body.devOtp };
  assert.equal((await t.call('POST', '/auth/verify-otp', { body })).status, 200);
  assert.equal((await t.call('POST', '/auth/verify-otp', { body })).status, 401);
  assert.equal((await t.call('POST', '/auth/request-otp', { body: { phone: '98970 12345' } })).status, 400);
});

test('OTP requests are rate limited', async () => {
  const phone = '+919500000003';
  const codes = [];
  for (let i = 0; i < 6; i++) codes.push((await t.call('POST', '/auth/request-otp', { body: { phone } })).status);
  assert.deepEqual(codes, [200, 200, 200, 200, 200, 429]);
});

test('refresh rotates tokens; reuse of an old refresh token revokes the family', async () => {
  const s = await t.login('+919500000004');
  const r1 = await t.call('POST', '/auth/refresh', { body: { refreshToken: s.refreshToken } });
  assert.equal(r1.status, 200);
  const reuse = await t.call('POST', '/auth/refresh', { body: { refreshToken: s.refreshToken } });
  assert.equal(reuse.status, 401);
  assert.equal(reuse.body.error.code, 'REFRESH_TOKEN_REUSED');
  const r2 = await t.call('POST', '/auth/refresh', { body: { refreshToken: r1.body.refreshToken } });
  assert.equal(r2.status, 401, 'whole family revoked');
});

test('logout revokes the refresh token', async () => {
  const s = await t.login('+919500000005');
  await t.call('POST', '/auth/logout', { body: { refreshToken: s.refreshToken } });
  assert.equal((await t.call('POST', '/auth/refresh', { body: { refreshToken: s.refreshToken } })).status, 401);
});

test('tampered / expired / alg-none access tokens are rejected', async () => {
  const s = await t.login('+919500000006');
  const [h, p, sig] = s.accessToken.split('.');
  const payload = JSON.parse(Buffer.from(p, 'base64url'));
  const forged = `${h}.${Buffer.from(JSON.stringify({ ...payload, sub: 'usr_someone_else' })).toString('base64url')}.${sig}`;
  assert.equal((await t.call('GET', '/users/me', { token: forged })).status, 401);
  const none = `${Buffer.from('{"alg":"none"}').toString('base64url')}.${p}.`;
  assert.equal((await t.call('GET', '/users/me', { token: none })).status, 401);
  const expired = auth.signJwt(t.app.cfg, { ...payload, exp: 1 });
  assert.equal((await t.call('GET', '/users/me', { token: expired })).status, 401);
  assert.equal((await t.call('GET', '/wallet')).status, 401);
});

test('profile PATCH is partial and validated', async () => {
  const s = await t.login('+919500000007');
  const a = await t.call('PATCH', '/users/me', { token: s.accessToken, body: { name: 'Asha' } });
  assert.equal(a.body.name, 'Asha');
  const b = await t.call('PATCH', '/users/me', { token: s.accessToken, body: { emergencyContactName: 'Ravi', autoDialSos: true } });
  assert.equal(b.body.name, 'Asha', 'untouched fields are not overwritten');
  assert.equal(b.body.autoDialSos, true);
  assert.equal((await t.call('PATCH', '/users/me', { token: s.accessToken, body: { email: 'nope' } })).status, 400);
});

test('addresses CRUD is scoped to the owner', async () => {
  const a = await t.login('+919500000008');
  const b = await t.login('+919500000009');
  const add = await t.call('POST', '/users/me/addresses', { token: a.accessToken, body: { label: 'Home', name: 'Clock Tower', lat: 30.3255, lng: 78.0436 } });
  assert.equal(add.status, 201);
  assert.equal((await t.call('GET', '/users/me/addresses', { token: b.accessToken })).body.addresses.length, 0);
  assert.equal((await t.call('DELETE', `/users/me/addresses/${add.body.addressId}`, { token: b.accessToken })).status, 404);
  assert.equal((await t.call('DELETE', `/users/me/addresses/${add.body.addressId}`, { token: a.accessToken })).status, 200);
});

test('account deletion: blocked with funds, then anonymises and revokes sessions', async () => {
  const s = await t.login('+919500000010');
  await t.topUp(s.accessToken, 1000);
  const blocked = await t.call('DELETE', '/users/me', { token: s.accessToken });
  assert.equal(blocked.status, 409);
  assert.equal(blocked.body.error.code, 'WALLET_NOT_EMPTY');
  const e = await t.login('+919500000011');
  assert.equal((await t.call('DELETE', '/users/me', { token: e.accessToken })).status, 200);
  assert.equal((await t.call('GET', '/users/me', { token: e.accessToken })).status, 401);
  assert.equal((await t.call('POST', '/auth/refresh', { body: { refreshToken: e.refreshToken } })).status, 401);
});

test('SOS never claims success without an integration', async () => {
  const s = await t.login('+919500000012');
  const r = await t.call('POST', '/safety/sos', { token: s.accessToken, body: { lat: 30.3, lng: 78.0 } });
  assert.equal(r.status, 503);
  assert.equal(r.body.error.code, 'SOS_NOT_CONFIGURED');
});

test('auth provider not configured -> 503', async () => {
  const u = await startApp({ env: { AUTH_PROVIDER: '' } });
  try {
    assert.equal((await u.call('POST', '/auth/request-otp', { body: { phone: '+919500000013' } })).status, 503);
  } finally {
    await u.close();
  }
});

test('production config refuses test adapters and missing secrets', () => {
  const { load } = require('../src/config');
  assert.throws(() => load({ NODE_ENV: 'production' }), /JWT_SECRET/);
  const base = { NODE_ENV: 'production', JWT_SECRET: 'x', OTP_PEPPER: 'y' };
  assert.throws(() => load({ ...base, PAYMENT_PROVIDER: 'test' }), /forbidden/);
  assert.throws(() => load({ ...base, AUTH_PROVIDER: 'dev' }), /forbidden/);
  assert.throws(() => load({ ...base, MAPS_PROVIDER: 'dev' }), /forbidden/);
  assert.throws(() => load({ ...base, DEV_PILOT_SIMULATOR: 'true' }), /forbidden/);
  assert.doesNotThrow(() => load(base));
});
