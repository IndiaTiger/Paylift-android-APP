'use strict';
const { createApp } = require('../src/server');

const TEST_ENV = {
  NODE_ENV: 'test',
  AUTH_PROVIDER: 'dev',
  PAYMENT_PROVIDER: 'test',
  MAPS_PROVIDER: 'dev',
  ADMIN_API_TOKEN: 'test-admin-token-not-a-secret',
  SEARCH_TIMEOUT_SEC: '120',
};

async function startApp({ env = {}, seedDevPilots = true } = {}) {
  const app = createApp({ env: { ...TEST_ENV, ...env }, dbFile: ':memory:', quiet: true, seedDevPilots });
  const addr = await app.listen(0, '127.0.0.1');
  const base = `http://127.0.0.1:${addr.port}`;

  async function call(method, path, { body, token, headers = {}, raw } = {}) {
    const h = { ...headers };
    if (body !== undefined || raw !== undefined) h['Content-Type'] = 'application/json';
    if (token) h.Authorization = `Bearer ${token}`;
    const res = await fetch(base + path, { method, headers: h, body: raw !== undefined ? raw : body !== undefined ? JSON.stringify(body) : undefined });
    const json = await res.json();
    return { status: res.status, body: json };
  }

  async function login(phone) {
    const otp = await call('POST', '/auth/request-otp', { body: { phone } });
    if (otp.status !== 200) throw new Error(`request-otp ${otp.status} ${JSON.stringify(otp.body)}`);
    const v = await call('POST', '/auth/verify-otp', { body: { phone, requestId: otp.body.requestId, otp: otp.body.devOtp } });
    if (v.status !== 200) throw new Error(`verify-otp ${v.status}`);
    return v.body;
  }

  /** Top up through the full test-gateway flow (order -> checkout -> verify). */
  async function topUp(token, amountPaise, key = `k-${Math.random()}`) {
    const order = await call('POST', '/payments/orders', { token, body: { amountPaise }, headers: { 'Idempotency-Key': key } });
    const checkout = await call('POST', '/payments/test/checkout', { token, body: { paymentId: order.body.paymentId, outcome: 'SUCCESS', deliverWebhook: false } });
    const verify = await call('POST', '/payments/verify', { token, body: { paymentId: order.body.paymentId, providerPaymentId: checkout.body.providerPaymentId, signature: checkout.body.signature } });
    return { order, checkout, verify };
  }

  const CLOCK_TOWER = { name: 'Clock Tower', subtitle: 'Dehradun', lat: 30.3255, lng: 78.0436 };
  const RAJPUR = { name: 'Rajpur Road', subtitle: 'Dehradun', lat: 30.3580, lng: 78.0720 };

  async function quote(token, vehicleId = 'cab_mini', pickup = CLOCK_TOWER, dropoff = RAJPUR) {
    return call('POST', '/rides/quote', { token, body: { vehicleId, pickup, dropoff } });
  }

  async function requestRide(token, { vehicleId = 'cab_mini', key = `r-${Math.random()}` } = {}) {
    const q = await quote(token, vehicleId);
    const r = await call('POST', '/rides', { token, body: { quoteId: q.body.quoteId, expectedTotalPaise: q.body.fare.totalPaise }, headers: { 'Idempotency-Key': key } });
    return { quote: q, ride: r };
  }

  const pilotSessions = {};
  const pilotLogin = async (i = 0) => (pilotSessions[i] ??= await login(require('../src/pilots').DEV_PILOTS[i].phone));
  const admin = { 'X-Admin-Token': TEST_ENV.ADMIN_API_TOKEN };

  return { app, base, call, login, topUp, quote, requestRide, pilotLogin, admin, CLOCK_TOWER, RAJPUR, close: () => app.close() };
}

module.exports = { startApp, TEST_ENV };
