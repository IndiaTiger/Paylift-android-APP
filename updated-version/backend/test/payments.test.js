'use strict';
// Payment test matrix (PaymentTestAdapter): all ten required scenarios.
const test = require('node:test');
const assert = require('node:assert/strict');
const { startApp } = require('./helpers');
const payments = require('../src/payments');
const ledger = require('../src/ledger');

let t;
test.before(async () => { t = await startApp(); });
test.after(() => t.close());

const wallet = async (token) => (await t.call('GET', '/wallet', { token })).body;

test('new accounts start with a zero balance (no free money)', async () => {
  const s = await t.login('+919800000001');
  assert.deepEqual(await wallet(s.accessToken), { balancePaise: 0, heldPaise: 0, availablePaise: 0, currency: 'INR' });
});

test('1. successful payment credits exactly once after server verification', async () => {
  const s = await t.login('+919800000002');
  const { verify } = await t.topUp(s.accessToken, 50000);
  assert.equal(verify.status, 200);
  assert.equal(verify.body.payment.status, 'SUCCESS');
  assert.equal((await wallet(s.accessToken)).balancePaise, 50000);
});

test('2. failed payment does not credit', async () => {
  const s = await t.login('+919800000003');
  const order = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 20000 }, headers: { 'Idempotency-Key': 'f1' } });
  await t.call('POST', '/payments/test/checkout', { token: s.accessToken, body: { paymentId: order.body.paymentId, outcome: 'FAILED' } });
  const v = await t.call('POST', '/payments/verify', { token: s.accessToken, body: { paymentId: order.body.paymentId, outcome: 'FAILED' } });
  assert.equal(v.body.payment.status, 'FAILED');
  await new Promise((r) => setTimeout(r, 50)); // allow the failure webhook to land
  assert.equal((await wallet(s.accessToken)).balancePaise, 0);
});

test('3. cancelled payment does not credit and cannot be resurrected', async () => {
  const s = await t.login('+919800000004');
  const order = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 20000 }, headers: { 'Idempotency-Key': 'c1' } });
  const v = await t.call('POST', '/payments/verify', { token: s.accessToken, body: { paymentId: order.body.paymentId, outcome: 'CANCELLED' } });
  assert.equal(v.body.payment.status, 'CANCELLED');
  // A late (forged or real) success for a cancelled order is rejected, not credited.
  const late = await t.call('POST', '/payments/test/checkout', { token: s.accessToken, body: { paymentId: order.body.paymentId, outcome: 'SUCCESS', deliverWebhook: false } });
  const v2 = await t.call('POST', '/payments/verify', { token: s.accessToken, body: { paymentId: order.body.paymentId, providerPaymentId: late.body.providerPaymentId, signature: late.body.signature } });
  assert.equal(v2.status, 409);
  assert.equal((await wallet(s.accessToken)).balancePaise, 0);
});

test('4. duplicate payment: same idempotency key -> same order; double verify credits once', async () => {
  const s = await t.login('+919800000005');
  const a = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 10000 }, headers: { 'Idempotency-Key': 'dup' } });
  const b = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 10000 }, headers: { 'Idempotency-Key': 'dup' } });
  assert.equal(a.body.paymentId, b.body.paymentId);
  const c = await t.call('POST', '/payments/test/checkout', { token: s.accessToken, body: { paymentId: a.body.paymentId, outcome: 'SUCCESS', deliverWebhook: false } });
  const body = { paymentId: a.body.paymentId, providerPaymentId: c.body.providerPaymentId, signature: c.body.signature };
  const results = await Promise.all([1, 2, 3, 4, 5].map(() => t.call('POST', '/payments/verify', { token: s.accessToken, body })));
  results.forEach((r) => assert.equal(r.status, 200));
  assert.equal((await wallet(s.accessToken)).balancePaise, 10000);
  // Same key with a different amount is rejected.
  const conflict = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 99900 }, headers: { 'Idempotency-Key': 'dup' } });
  assert.equal(conflict.status, 409);
});

test('5. duplicate webhook credits once', async () => {
  const s = await t.login('+919800000006');
  const o = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 30000 }, headers: { 'Idempotency-Key': 'wh' } });
  const adapter = payments.adapterFor(t.app.cfg);
  const raw = JSON.stringify({ id: 'evt_dup_1', type: 'payment.captured', orderId: o.body.providerOrderId, providerPaymentId: 'pay_x1', amountPaise: 30000 });
  const sig = adapter.signWebhook(raw);
  const r = await Promise.all([1, 2, 3].map(() => t.call('POST', '/payments/webhook', { raw, headers: { 'X-PayLift-Signature': sig } })));
  r.forEach((x) => assert.equal(x.status, 200));
  // Different event id for the same capture also cannot double credit.
  const raw2 = JSON.stringify({ id: 'evt_dup_2', type: 'payment.captured', orderId: o.body.providerOrderId, providerPaymentId: 'pay_x1', amountPaise: 30000 });
  const r2 = await t.call('POST', '/payments/webhook', { raw: raw2, headers: { 'X-PayLift-Signature': adapter.signWebhook(raw2) } });
  assert.equal(r2.body.outcome, 'ALREADY_SETTLED');
  assert.equal((await wallet(s.accessToken)).balancePaise, 30000);
});

test('6. incorrect amount in webhook is rejected and the payment fails', async () => {
  const s = await t.login('+919800000007');
  const o = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 30000 }, headers: { 'Idempotency-Key': 'amt' } });
  const adapter = payments.adapterFor(t.app.cfg);
  const raw = JSON.stringify({ id: 'evt_amt', type: 'payment.captured', orderId: o.body.providerOrderId, providerPaymentId: 'pay_amt', amountPaise: 3000000 });
  const r = await t.call('POST', '/payments/webhook', { raw, headers: { 'X-PayLift-Signature': adapter.signWebhook(raw) } });
  assert.equal(r.body.outcome, 'REJECTED_AMOUNT_MISMATCH');
  const p = await t.call('GET', `/payments/${o.body.paymentId}`, { token: s.accessToken });
  assert.equal(p.body.status, 'FAILED');
  assert.equal((await wallet(s.accessToken)).balancePaise, 0);
  // Amount limits are enforced server-side at order creation.
  const tooBig = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 99999999 }, headers: { 'Idempotency-Key': 'big' } });
  assert.equal(tooBig.status, 400);
  const fractional = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 100.5 }, headers: { 'Idempotency-Key': 'frac' } });
  assert.equal(fractional.status, 400);
});

test('7. invalid signatures are rejected (checkout and webhook)', async () => {
  const s = await t.login('+919800000008');
  const o = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 10000 }, headers: { 'Idempotency-Key': 'sig' } });
  const forged = await t.call('POST', '/payments/verify', { token: s.accessToken, body: { paymentId: o.body.paymentId, providerPaymentId: 'pay_forged', signature: 'a'.repeat(64) } });
  assert.equal(forged.status, 400);
  assert.equal(forged.body.error.code, 'INVALID_SIGNATURE');
  const raw = JSON.stringify({ id: 'evt_forged', type: 'payment.captured', orderId: o.body.providerOrderId, providerPaymentId: 'p', amountPaise: 10000 });
  const wh = await t.call('POST', '/payments/webhook', { raw, headers: { 'X-PayLift-Signature': 'deadbeef' } });
  assert.equal(wh.status, 401);
  assert.equal((await wallet(s.accessToken)).balancePaise, 0);
});

test('8. refund: operator-only, bounded, idempotent, debits wallet', async () => {
  const s = await t.login('+919800000009');
  const { order } = await t.topUp(s.accessToken, 40000);
  const pid = order.body.paymentId;
  const noAuth = await t.call('POST', `/payments/${pid}/refund`, { token: s.accessToken, body: { refundId: 'r1' } });
  assert.equal(noAuth.status, 403, 'riders cannot create refunds');
  const r1 = await t.call('POST', `/payments/${pid}/refund`, { headers: t.admin, body: { refundId: 'r1', amountPaise: 15000 } });
  assert.equal(r1.status, 200);
  const again = await t.call('POST', `/payments/${pid}/refund`, { headers: t.admin, body: { refundId: 'r1', amountPaise: 15000 } });
  assert.equal(again.status, 200);
  assert.equal((await wallet(s.accessToken)).balancePaise, 25000, 'replayed refund applied once');
  const over = await t.call('POST', `/payments/${pid}/refund`, { headers: t.admin, body: { refundId: 'r2', amountPaise: 30000 } });
  assert.equal(over.status, 400, 'cannot refund more than remains');
  const rest = await t.call('POST', `/payments/${pid}/refund`, { headers: t.admin, body: { refundId: 'r3' } });
  assert.equal(rest.body.status, 'REFUNDED');
  assert.equal((await wallet(s.accessToken)).balancePaise, 0);
});

test('9. payment success + app crash: webhook alone settles the payment', async () => {
  const s = await t.login('+919800000010');
  const o = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 12300 }, headers: { 'Idempotency-Key': 'crash' } });
  // The app dies right after the gateway succeeded: it never calls /payments/verify.
  await t.call('POST', '/payments/test/checkout', { token: s.accessToken, body: { paymentId: o.body.paymentId, outcome: 'SUCCESS' } });
  await new Promise((r) => setTimeout(r, 50));
  const p = await t.call('GET', `/payments/${o.body.paymentId}`, { token: s.accessToken });
  assert.equal(p.body.status, 'SUCCESS');
  assert.equal((await wallet(s.accessToken)).balancePaise, 12300);
});

test('10. payment success + wallet credit retry: verify after webhook is a no-op', async () => {
  const s = await t.login('+919800000011');
  const o = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 45600 }, headers: { 'Idempotency-Key': 'retry' } });
  const c = await t.call('POST', '/payments/test/checkout', { token: s.accessToken, body: { paymentId: o.body.paymentId, outcome: 'SUCCESS' } });
  await new Promise((r) => setTimeout(r, 50));
  for (let i = 0; i < 3; i++) {
    const v = await t.call('POST', '/payments/verify', { token: s.accessToken, body: { paymentId: o.body.paymentId, providerPaymentId: c.body.providerPaymentId, signature: c.body.signature } });
    assert.equal(v.status, 200);
  }
  // Direct ledger retry is also idempotent.
  const u = t.app.db.prepare('SELECT user_id FROM payments WHERE id = ?').get(o.body.paymentId);
  assert.equal(ledger.creditTopup(t.app.db, u.user_id, o.body.paymentId, 45600).replayed, true);
  assert.equal((await wallet(s.accessToken)).balancePaise, 45600);
  assert.ok(ledger.reconcile(t.app.db, u.user_id).ok);
});

test('fake payment cannot credit: client-reported SUCCESS without provider signature is rejected', async () => {
  const s = await t.login('+919800000012');
  const o = await t.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 10000 }, headers: { 'Idempotency-Key': 'fake' } });
  const r = await t.call('POST', '/payments/verify', { token: s.accessToken, body: { paymentId: o.body.paymentId, outcome: 'SUCCESS' } });
  assert.equal(r.status, 400);
  assert.equal((await wallet(s.accessToken)).balancePaise, 0);
});

test('users cannot read or verify other users payments', async () => {
  const a = await t.login('+919800000013');
  const b = await t.login('+919800000014');
  const o = await t.call('POST', '/payments/orders', { token: a.accessToken, body: { amountPaise: 10000 }, headers: { 'Idempotency-Key': 'own' } });
  assert.equal((await t.call('GET', `/payments/${o.body.paymentId}`, { token: b.accessToken })).status, 404);
  assert.equal((await t.call('POST', '/payments/test/checkout', { token: b.accessToken, body: { paymentId: o.body.paymentId, outcome: 'SUCCESS' } })).status, 404);
});

test('payment provider not configured -> 503, never a fake success', async () => {
  const u = await startApp({ env: { PAYMENT_PROVIDER: '' } });
  try {
    const s = await u.login('+919800000015');
    const r = await u.call('POST', '/payments/orders', { token: s.accessToken, body: { amountPaise: 10000 }, headers: { 'Idempotency-Key': 'x' } });
    assert.equal(r.status, 503);
    assert.equal(r.body.error.code, 'PAYMENT_PROVIDER_NOT_CONFIGURED');
  } finally {
    await u.close();
  }
});
