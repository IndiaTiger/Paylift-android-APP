'use strict';
// Demo login: fixed credentials honoured only by the dev OTP adapter, never in production.
const test = require('node:test');
const assert = require('node:assert/strict');
const { startApp } = require('./helpers');
const { load } = require('../src/config');

const DEMO = { DEMO_LOGIN_PHONE: '+919999999999', DEMO_LOGIN_OTP: '123456' };

test('demo credentials work in demo (dev) mode', async () => {
  const t = await startApp({ env: DEMO });
  try {
    const r = await t.call('POST', '/auth/request-otp', { body: { phone: '+919999999999' } });
    const v = await t.call('POST', '/auth/verify-otp', { body: { phone: '+919999999999', requestId: r.body.requestId, otp: '123456' } });
    assert.equal(v.status, 200);
    assert.ok(v.body.accessToken);
    // A new demo account still starts with an empty wallet.
    assert.equal((await t.call('GET', '/wallet', { token: v.body.accessToken })).body.balancePaise, 0);
  } finally {
    await t.close();
  }
});

test('wrong demo OTP fails and counts toward the attempt limit', async () => {
  const t = await startApp({ env: DEMO });
  try {
    const r = await t.call('POST', '/auth/request-otp', { body: { phone: '+919999999999' } });
    for (let i = 0; i < 5; i++) {
      assert.equal((await t.call('POST', '/auth/verify-otp', { body: { phone: '+919999999999', requestId: r.body.requestId, otp: '654321' } })).status, 401);
    }
    const locked = await t.call('POST', '/auth/verify-otp', { body: { phone: '+919999999999', requestId: r.body.requestId, otp: '123456' } });
    assert.equal(locked.status, 429, 'lockout still applies to the demo code');
  } finally {
    await t.close();
  }
});

test('the demo code is not valid for any other phone', async () => {
  const t = await startApp({ env: DEMO });
  try {
    const phone = '+919888888888';
    const r = await t.call('POST', '/auth/request-otp', { body: { phone } });
    if (r.body.devOtp === '123456') return; // 1-in-a-million random collision
    assert.equal((await t.call('POST', '/auth/verify-otp', { body: { phone, requestId: r.body.requestId, otp: '123456' } })).status, 401);
  } finally {
    await t.close();
  }
});

test('demo credentials are refused in production mode', () => {
  const base = { NODE_ENV: 'production', JWT_SECRET: 'x', OTP_PEPPER: 'y' };
  assert.throws(() => load({ ...base, ...DEMO }), /DEMO_LOGIN_\* is forbidden in production/);
  assert.throws(() => load({ ...base, AUTH_PROVIDER: 'dev' }), /forbidden/);
});

test('without the dev adapter the demo phone gets no fixed code (provider not configured)', async () => {
  const t = await startApp({ env: { ...DEMO, AUTH_PROVIDER: '' } });
  try {
    assert.equal((await t.call('POST', '/auth/request-otp', { body: { phone: '+919999999999' } })).status, 503);
  } finally {
    await t.close();
  }
});

test('production OTP protections are unchanged: single use + rate limit apply to demo phone', async () => {
  const t = await startApp({ env: DEMO });
  try {
    const r = await t.call('POST', '/auth/request-otp', { body: { phone: '+919999999999' } });
    const body = { phone: '+919999999999', requestId: r.body.requestId, otp: '123456' };
    assert.equal((await t.call('POST', '/auth/verify-otp', { body })).status, 200);
    assert.equal((await t.call('POST', '/auth/verify-otp', { body })).status, 401, 'single use');
    const statuses = [];
    for (let i = 0; i < 5; i++) statuses.push((await t.call('POST', '/auth/request-otp', { body: { phone: '+919999999999' } })).status);
    assert.equal(statuses.at(-1), 429, 'rate limit');
  } finally {
    await t.close();
  }
});
