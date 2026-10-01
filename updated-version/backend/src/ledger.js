'use strict';
// Wallet ledger. The ONLY module allowed to change wallet balances.
//
// Rules:
//  * amounts are positive integer paise;
//  * every movement writes exactly one immutable ledger entry whose `reference` is UNIQUE, so the
//    same business event (a payment, a ride hold, a capture ...) can be applied at most once;
//  * balance changes are single conditional UPDATE statements (no read-modify-write), executed in
//    the same transaction as the ledger insert;
//  * there is no API to create money: credits only come from verified payments (TOPUP_CREDIT) or
//    refunds of a previously captured ride (RIDE_REFUND, bounded by the capture amount).
const { ApiError, id } = require('./util');

function ensureWallet(db, userId) {
  db.prepare('INSERT OR IGNORE INTO wallets (user_id, balance_paise, held_paise, updated_at) VALUES (?, 0, 0, ?)')
    .run(userId, Date.now());
}

function getWallet(db, userId) {
  ensureWallet(db, userId);
  const w = db.prepare('SELECT balance_paise, held_paise, currency FROM wallets WHERE user_id = ?').get(userId);
  return {
    balancePaise: w.balance_paise,
    heldPaise: w.held_paise,
    availablePaise: w.balance_paise - w.held_paise,
    currency: w.currency,
  };
}

function findByReference(db, reference) {
  return db.prepare('SELECT * FROM ledger_entries WHERE reference = ?').get(reference);
}

function checkAmount(amount) {
  if (!Number.isSafeInteger(amount) || amount <= 0) throw new ApiError(400, 'INVALID_AMOUNT', 'Amount must be a positive integer (paise)');
}

// Applies one movement. `update` is a conditional UPDATE that must affect exactly 1 row.
// If the reference already exists the existing entry is returned (idempotent replay) - the
// caller can tell via `replayed`.
function apply(db, { userId, type, amount, reference, update, updateArgs, failCode, failMessage, paymentId = null, rideId = null, relatedEntryId = null, description }) {
  checkAmount(amount);
  return db.tx(() => {
    const existing = findByReference(db, reference);
    if (existing) {
      if (existing.user_id !== userId || existing.type !== type || existing.amount_paise !== amount) {
        throw new ApiError(409, 'REFERENCE_CONFLICT', `Ledger reference ${reference} already used for a different movement`);
      }
      return { entry: existing, replayed: true };
    }
    ensureWallet(db, userId);
    const res = db.prepare(update).run(...updateArgs, Date.now(), userId);
    if (res.changes !== 1) throw new ApiError(409, failCode, failMessage);
    const w = db.prepare('SELECT balance_paise, held_paise FROM wallets WHERE user_id = ?').get(userId);
    const entry = {
      id: id('le'),
      user_id: userId,
      type,
      amount_paise: amount,
      balance_after_paise: w.balance_paise,
      held_after_paise: w.held_paise,
      reference,
      payment_id: paymentId,
      ride_id: rideId,
      related_entry_id: relatedEntryId,
      description,
      created_at: Date.now(),
    };
    db.prepare(`INSERT INTO ledger_entries (id, user_id, type, amount_paise, balance_after_paise, held_after_paise,
                reference, payment_id, ride_id, related_entry_id, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .run(entry.id, userId, type, amount, entry.balance_after_paise, entry.held_after_paise, reference,
        paymentId, rideId, relatedEntryId, description, entry.created_at);
    return { entry, replayed: false };
  });
}

/** Credit a verified payment. Idempotent per payment. */
function creditTopup(db, userId, paymentId, amount) {
  return apply(db, {
    userId, type: 'TOPUP_CREDIT', amount, reference: `topup:${paymentId}`, paymentId,
    update: 'UPDATE wallets SET balance_paise = balance_paise + ?, updated_at = ? WHERE user_id = ?',
    updateArgs: [amount], failCode: 'WALLET_ERROR', failMessage: 'Wallet update failed',
    description: 'Wallet top-up',
  });
}

/** Reserve the ride fare. Fails atomically if available funds are insufficient. */
function holdForRide(db, userId, rideId, amount) {
  return apply(db, {
    userId, type: 'RIDE_HOLD', amount, reference: `hold:${rideId}`, rideId,
    update: 'UPDATE wallets SET held_paise = held_paise + ?, updated_at = ? WHERE user_id = ? AND balance_paise - held_paise >= ' + amount,
    updateArgs: [amount], failCode: 'INSUFFICIENT_FUNDS', failMessage: 'Insufficient wallet balance',
    description: 'Fare held for ride',
  });
}

function requireHold(db, rideId) {
  const hold = findByReference(db, `hold:${rideId}`);
  if (!hold) throw new ApiError(409, 'NO_HOLD', `No hold exists for ride ${rideId}`);
  return hold;
}

/** Release (part of) a hold without charging. */
function releaseHold(db, userId, rideId, amount, description = 'Hold released') {
  const hold = requireHold(db, rideId);
  if (amount > hold.amount_paise) throw new ApiError(409, 'RELEASE_EXCEEDS_HOLD', 'Release exceeds hold');
  return apply(db, {
    userId, type: 'HOLD_RELEASE', amount, reference: `release:${rideId}`, rideId, relatedEntryId: hold.id,
    update: 'UPDATE wallets SET held_paise = held_paise - ?, updated_at = ? WHERE user_id = ? AND held_paise >= ' + amount,
    updateArgs: [amount], failCode: 'HOLD_STATE_ERROR', failMessage: 'Held funds are inconsistent',
    description,
  });
}

/** Charge (part of) a held amount: moves funds out of both held and balance. */
function captureHold(db, userId, rideId, amount, { type = 'RIDE_CAPTURE', description = 'Ride fare charged' } = {}) {
  const hold = requireHold(db, rideId);
  if (amount > hold.amount_paise) throw new ApiError(409, 'CAPTURE_EXCEEDS_HOLD', 'Capture exceeds hold');
  const prefix = type === 'CANCELLATION_FEE' ? 'cancelfee' : 'capture';
  return apply(db, {
    userId, type, amount, reference: `${prefix}:${rideId}`, rideId, relatedEntryId: hold.id,
    update: 'UPDATE wallets SET held_paise = held_paise - ?, balance_paise = balance_paise - ?, updated_at = ? WHERE user_id = ? AND held_paise >= ' + amount,
    updateArgs: [amount, amount], failCode: 'HOLD_STATE_ERROR', failMessage: 'Held funds are inconsistent',
    description,
  });
}

/**
 * Refund (part of) a previously captured ride charge back to the wallet. Requires the original
 * capture entry, and the sum of refunds can never exceed the captured amount.
 */
function refundRide(db, userId, rideId, refundId, amount) {
  return db.tx(() => {
    const capture = findByReference(db, `capture:${rideId}`) || findByReference(db, `cancelfee:${rideId}`);
    if (!capture || capture.user_id !== userId) throw new ApiError(409, 'NO_ORIGINAL_CHARGE', 'Refund requires an original ride charge');
    const refunded = db.prepare("SELECT COALESCE(SUM(amount_paise), 0) s FROM ledger_entries WHERE type = 'RIDE_REFUND' AND related_entry_id = ? AND reference != ?")
      .get(capture.id, `refund:${refundId}`).s;
    if (refunded + amount > capture.amount_paise) throw new ApiError(409, 'REFUND_EXCEEDS_CHARGE', 'Refund exceeds the original charge');
    return apply(db, {
      userId, type: 'RIDE_REFUND', amount, reference: `refund:${refundId}`, rideId, relatedEntryId: capture.id,
      update: 'UPDATE wallets SET balance_paise = balance_paise + ?, updated_at = ? WHERE user_id = ?',
      updateArgs: [amount], failCode: 'WALLET_ERROR', failMessage: 'Wallet update failed',
      description: 'Ride refund',
    });
  });
}

/** Return (part of) a top-up to the original payment instrument; debits available funds. */
function debitTopupRefund(db, userId, paymentId, refundId, amount) {
  return apply(db, {
    userId, type: 'TOPUP_REFUND', amount, reference: `topuprefund:${refundId}`, paymentId,
    relatedEntryId: (findByReference(db, `topup:${paymentId}`) || {}).id || null,
    update: 'UPDATE wallets SET balance_paise = balance_paise - ?, updated_at = ? WHERE user_id = ? AND balance_paise - held_paise >= ' + amount,
    updateArgs: [amount], failCode: 'INSUFFICIENT_FUNDS', failMessage: 'Refunded funds were already spent',
    description: 'Top-up refunded to original payment method',
  });
}

function listEntries(db, userId, { limit = 50, before } = {}) {
  const rows = before
    ? db.prepare('SELECT * FROM ledger_entries WHERE user_id = ? AND created_at < ? ORDER BY created_at DESC, id DESC LIMIT ?').all(userId, before, limit)
    : db.prepare('SELECT * FROM ledger_entries WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?').all(userId, limit);
  return rows.map(toDto);
}

function toDto(e) {
  return {
    id: e.id,
    type: e.type,
    amountPaise: e.amount_paise,
    balanceAfterPaise: e.balance_after_paise,
    heldAfterPaise: e.held_after_paise,
    reference: e.reference,
    paymentId: e.payment_id,
    rideId: e.ride_id,
    relatedEntryId: e.related_entry_id,
    description: e.description,
    createdAt: e.created_at,
  };
}

/** Invariant check used by tests: the wallet equals the fold of its ledger. */
function reconcile(db, userId) {
  const sign = { TOPUP_CREDIT: [1, 0], RIDE_HOLD: [0, 1], HOLD_RELEASE: [0, -1], RIDE_CAPTURE: [-1, -1], CANCELLATION_FEE: [-1, -1], RIDE_REFUND: [1, 0], TOPUP_REFUND: [-1, 0] };
  let balance = 0;
  let held = 0;
  for (const e of db.prepare('SELECT type, amount_paise FROM ledger_entries WHERE user_id = ?').all(userId)) {
    balance += sign[e.type][0] * e.amount_paise;
    held += sign[e.type][1] * e.amount_paise;
  }
  const w = getWallet(db, userId);
  return { ok: w.balancePaise === balance && w.heldPaise === held, ledger: { balance, held }, wallet: w };
}

module.exports = { ensureWallet, getWallet, creditTopup, holdForRide, releaseHold, captureHold, refundRide, debitTopupRefund, listEntries, toDto, reconcile, findByReference };
