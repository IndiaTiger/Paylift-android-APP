'use strict';
// Payments: order creation -> provider checkout -> client-reported result -> server verification
// -> webhook -> idempotent ledger credit.
//
// The client never decides that money was received. A payment becomes SUCCESS only when the
// server verifies a provider signature (checkout callback) or a signed webhook, and the credit is
// applied through ledger.creditTopup whose UNIQUE reference makes it idempotent.
//
// Provider adapters:
//   test : deterministic local gateway (PaymentTestAdapter). Test/dev only - refused in production.
//   <real provider> : NOT implemented in this repository. A production adapter must implement
//          createOrder / verifyCheckoutSignature / verifyWebhook / refund with the gateway's SDK and
//          the PAYMENT_KEY_SECRET + PAYMENT_WEBHOOK_SECRET from the server environment.
const { ApiError, id, hmacHex, safeEqual, requireInt, requireString } = require('./util');
const ledger = require('./ledger');

function testAdapter(cfg) {
  return {
    name: 'test',
    publicKey: cfg.paymentKeyId,
    createOrder: (amountPaise) => ({ providerOrderId: `order_test_${id('o').slice(2)}`, amountPaise }),
    checkoutSignature: (orderId, providerPaymentId) => hmacHex(cfg.paymentKeySecret, `${orderId}|${providerPaymentId}`),
    verifyCheckoutSignature(orderId, providerPaymentId, signature) {
      return safeEqual(this.checkoutSignature(orderId, providerPaymentId), signature);
    },
    signWebhook: (rawBody) => hmacHex(cfg.paymentWebhookSecret, rawBody),
    verifyWebhook: (rawBody, signature) => safeEqual(hmacHex(cfg.paymentWebhookSecret, rawBody), signature || ''),
    refund: (providerPaymentId, amountPaise) => ({ providerRefundId: `rfnd_test_${id('r').slice(2)}`, amountPaise }),
  };
}

function adapterFor(cfg) {
  if (cfg.paymentProvider === 'test') return testAdapter(cfg);
  return null;
}

function requireAdapter(cfg) {
  const a = adapterFor(cfg);
  if (!a) throw new ApiError(503, 'PAYMENT_PROVIDER_NOT_CONFIGURED', 'Payment provider is not configured');
  return a;
}

function toDto(p) {
  return {
    paymentId: p.id,
    provider: p.provider,
    providerOrderId: p.provider_order_id,
    amountPaise: p.amount_paise,
    refundedPaise: p.refunded_paise,
    currency: p.currency,
    status: p.status,
    failureReason: p.failure_reason,
    createdAt: p.created_at,
    updatedAt: p.updated_at,
  };
}

function getOwned(db, userId, paymentId) {
  const p = db.prepare('SELECT * FROM payments WHERE id = ?').get(paymentId);
  if (!p || p.user_id !== userId) throw new ApiError(404, 'NOT_FOUND', 'Payment not found');
  return p;
}

function createOrder(db, cfg, user, body, idempotencyKey) {
  const adapter = requireAdapter(cfg);
  const amount = requireInt(body.amountPaise, 'amountPaise', { min: cfg.minTopupPaise, max: cfg.maxTopupPaise });
  const key = requireString(idempotencyKey, 'Idempotency-Key', { max: 100 });
  return db.tx(() => {
    const existing = db.prepare('SELECT * FROM payments WHERE user_id = ? AND idempotency_key = ?').get(user.id, key);
    if (existing) {
      if (existing.amount_paise !== amount) throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency key reused with a different amount');
      return { ...toDto(existing), publicKey: adapter.publicKey };
    }
    const order = adapter.createOrder(amount);
    const now = Date.now();
    const p = { id: id('pay'), user_id: user.id, provider: adapter.name, provider_order_id: order.providerOrderId, amount_paise: amount, refunded_paise: 0, currency: 'INR', status: 'PENDING', failure_reason: null, created_at: now, updated_at: now };
    db.prepare(`INSERT INTO payments (id, user_id, provider, provider_order_id, amount_paise, currency, status, idempotency_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'INR', 'PENDING', ?, ?, ?)`).run(p.id, user.id, p.provider, p.provider_order_id, amount, key, now, now);
    return { ...toDto(p), publicKey: adapter.publicKey };
  });
}

/**
 * Single settlement path used by both /payments/verify and the webhook. Idempotent:
 * repeated calls for the same payment never credit twice (ledger reference topup:<paymentId>).
 */
function settleSuccess(db, payment, providerPaymentId, reportedAmount) {
  const result = db.tx(() => {
    const p = db.prepare('SELECT * FROM payments WHERE id = ?').get(payment.id);
    if (reportedAmount !== undefined && reportedAmount !== p.amount_paise) {
      // Server-side amount validation: the gateway says a different amount was captured.
      if (p.status === 'PENDING') {
        db.prepare("UPDATE payments SET status = 'FAILED', failure_reason = 'AMOUNT_MISMATCH', updated_at = ? WHERE id = ?").run(Date.now(), p.id);
      }
      return { amountMismatch: true };
    }
    if (p.provider_payment_id && p.provider_payment_id !== providerPaymentId) {
      throw new ApiError(409, 'PAYMENT_REFERENCE_CONFLICT', 'Order already settled by a different provider payment');
    }
    if (p.status === 'SUCCESS' || p.status === 'REFUNDED') {
      return { payment: p, credited: false };
    }
    if (p.status !== 'PENDING') {
      // FAILED/CANCELLED orders are terminal; a late capture must be handled by ops (auto-refund at gateway).
      throw new ApiError(409, 'PAYMENT_NOT_PENDING', `Payment is ${p.status}`);
    }
    db.prepare("UPDATE payments SET status = 'SUCCESS', provider_payment_id = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'")
      .run(providerPaymentId, Date.now(), p.id);
    const { replayed } = ledger.creditTopup(db, p.user_id, p.id, p.amount_paise);
    return { payment: db.prepare('SELECT * FROM payments WHERE id = ?').get(p.id), credited: !replayed };
  });
  // Thrown after commit so the FAILED marking above persists.
  if (result.amountMismatch) throw new ApiError(422, 'AMOUNT_MISMATCH', 'Captured amount does not match the order');
  return result;
}

function markTerminal(db, paymentId, status, reason) {
  db.prepare('UPDATE payments SET status = ?, failure_reason = ?, updated_at = ? WHERE id = ? AND status = \'PENDING\'')
    .run(status, reason, Date.now(), paymentId);
}

function verify(db, cfg, user, body) {
  const adapter = requireAdapter(cfg);
  const p = getOwned(db, user.id, requireString(body.paymentId, 'paymentId', { max: 64 }));
  const outcome = body.outcome || 'SUCCESS';
  if (outcome === 'CANCELLED' || outcome === 'FAILED') {
    // Client-reported failure/cancellation can only move PENDING -> terminal; it can never credit.
    markTerminal(db, p.id, outcome, body.reason ? String(body.reason).slice(0, 200) : `CLIENT_${outcome}`);
    return { payment: toDto(db.prepare('SELECT * FROM payments WHERE id = ?').get(p.id)), wallet: ledger.getWallet(db, user.id) };
  }
  const providerPaymentId = requireString(body.providerPaymentId, 'providerPaymentId', { max: 100 });
  const signature = requireString(body.signature, 'signature', { max: 200 });
  if (!adapter.verifyCheckoutSignature(p.provider_order_id, providerPaymentId, signature)) {
    throw new ApiError(400, 'INVALID_SIGNATURE', 'Payment signature verification failed');
  }
  const { payment } = settleSuccess(db, p, providerPaymentId);
  return { payment: toDto(payment), wallet: ledger.getWallet(db, user.id) };
}

/** Webhook handler. `rawBody` must be the exact bytes received (signature is over raw body). */
function webhook(db, cfg, rawBody, signature) {
  const adapter = requireAdapter(cfg);
  if (!adapter.verifyWebhook(rawBody, signature)) throw new ApiError(401, 'INVALID_WEBHOOK_SIGNATURE', 'Webhook signature invalid');
  let event;
  try {
    event = JSON.parse(rawBody);
  } catch {
    throw new ApiError(400, 'INVALID_WEBHOOK', 'Malformed webhook body');
  }
  const eventId = requireString(event.id, 'event.id', { max: 100 });
  const seen = db.prepare('SELECT outcome FROM webhook_events WHERE event_id = ?').get(eventId);
  if (seen) return { ok: true, duplicate: true, outcome: seen.outcome };
  let outcome;
  const p = db.prepare('SELECT * FROM payments WHERE provider_order_id = ?').get(event.orderId);
  if (!p) {
    outcome = 'UNKNOWN_ORDER';
  } else if (event.type === 'payment.captured') {
    try {
      const r = settleSuccess(db, p, event.providerPaymentId, event.amountPaise);
      outcome = r.credited ? 'CREDITED' : 'ALREADY_SETTLED';
    } catch (e) {
      if (!(e instanceof ApiError)) throw e;
      outcome = `REJECTED_${e.code}`;
    }
  } else if (event.type === 'payment.failed') {
    markTerminal(db, p.id, 'FAILED', event.reason || 'GATEWAY_FAILED');
    outcome = 'MARKED_FAILED';
  } else {
    outcome = 'IGNORED';
  }
  db.prepare('INSERT OR IGNORE INTO webhook_events (event_id, provider, type, received_at, outcome) VALUES (?, ?, ?, ?, ?)')
    .run(eventId, adapter.name, String(event.type), Date.now(), outcome);
  return { ok: true, duplicate: false, outcome };
}

/**
 * Refund (part of) a successful top-up back to the original instrument. Operator-only endpoint:
 * the rider app cannot create refunds. Funds must still be available in the wallet.
 */
function refund(db, cfg, paymentId, body) {
  const adapter = requireAdapter(cfg);
  const refundId = requireString(body.refundId, 'refundId', { max: 64 });
  return db.tx(() => {
    const p = db.prepare('SELECT * FROM payments WHERE id = ?').get(paymentId);
    if (!p) throw new ApiError(404, 'NOT_FOUND', 'Payment not found');
    if (ledger.findByReference(db, `topuprefund:${refundId}`)) return toDto(p); // idempotent replay
    if (p.status !== 'SUCCESS') throw new ApiError(409, 'NOT_REFUNDABLE', `Payment is ${p.status}`);
    const amount = requireInt(body.amountPaise ?? p.amount_paise - p.refunded_paise, 'amountPaise', { min: 1, max: p.amount_paise - p.refunded_paise });
    ledger.debitTopupRefund(db, p.user_id, p.id, refundId, amount);
    adapter.refund(p.provider_payment_id, amount);
    const refunded = p.refunded_paise + amount;
    db.prepare('UPDATE payments SET refunded_paise = ?, status = ?, updated_at = ? WHERE id = ?')
      .run(refunded, refunded === p.amount_paise ? 'REFUNDED' : 'SUCCESS', Date.now(), p.id);
    return toDto(db.prepare('SELECT * FROM payments WHERE id = ?').get(p.id));
  });
}

/**
 * Test-gateway checkout simulator (PAYMENT_PROVIDER=test only). Plays the role of the hosted
 * checkout page: returns what a real SDK hands back to the app and, like a real gateway, sends
 * the signed webhook independently of the app.
 */
function testCheckout(db, cfg, user, body, deliverWebhook) {
  if (cfg.paymentProvider !== 'test') throw new ApiError(404, 'NOT_FOUND', 'Not found');
  const adapter = adapterFor(cfg);
  const p = getOwned(db, user.id, requireString(body.paymentId, 'paymentId', { max: 64 }));
  const outcome = body.outcome;
  if (!['SUCCESS', 'FAILED', 'CANCELLED'].includes(outcome)) throw new ApiError(400, 'VALIDATION_ERROR', 'outcome must be SUCCESS|FAILED|CANCELLED');
  if (outcome === 'CANCELLED') return { outcome };
  const providerPaymentId = `pay_test_${id('p').slice(2)}`;
  const event = outcome === 'SUCCESS'
    ? { id: `evt_${id('e').slice(2)}`, type: 'payment.captured', orderId: p.provider_order_id, providerPaymentId, amountPaise: p.amount_paise }
    : { id: `evt_${id('e').slice(2)}`, type: 'payment.failed', orderId: p.provider_order_id, providerPaymentId, reason: 'TEST_DECLINED' };
  const raw = JSON.stringify(event);
  if (deliverWebhook && body.deliverWebhook !== false) deliverWebhook(raw, adapter.signWebhook(raw));
  if (outcome === 'FAILED') return { outcome, providerPaymentId };
  return { outcome, providerPaymentId, signature: adapter.checkoutSignature(p.provider_order_id, providerPaymentId) };
}

module.exports = { createOrder, verify, webhook, refund, testCheckout, getOwned, toDto, adapterFor, settleSuccess };
