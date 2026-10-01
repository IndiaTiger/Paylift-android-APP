'use strict';
const { ApiError, id, requireString, requireLatLng } = require('./util');
const ledger = require('./ledger');
const rides = require('./rides');

function toDto(u) {
  return {
    userId: u.id,
    phone: u.phone,
    role: u.role,
    name: u.name,
    email: u.email,
    emergencyContactName: u.emergency_contact_name,
    emergencyContactPhone: u.emergency_contact_phone,
    emergencyRelationship: u.emergency_relationship,
    autoDialSos: !!u.auto_dial_sos,
    createdAt: u.created_at,
  };
}

const EDITABLE = {
  name: ['name', (v) => requireString(v, 'name', { min: 0, max: 80 })],
  email: ['email', (v) => (v === '' ? '' : requireString(v, 'email', { max: 120, pattern: /^[^@\s]+@[^@\s]+\.[^@\s]+$/ }))],
  emergencyContactName: ['emergency_contact_name', (v) => requireString(v, 'emergencyContactName', { min: 0, max: 80 })],
  emergencyContactPhone: ['emergency_contact_phone', (v) => (v === '' ? '' : requireString(v, 'emergencyContactPhone', { max: 20, pattern: /^\+?[0-9 ]{8,20}$/ }))],
  emergencyRelationship: ['emergency_relationship', (v) => requireString(v, 'emergencyRelationship', { min: 0, max: 60 })],
  autoDialSos: ['auto_dial_sos', (v) => { if (typeof v !== 'boolean') throw new ApiError(400, 'VALIDATION_ERROR', 'autoDialSos must be boolean'); return v ? 1 : 0; }],
};

/** Partial update: only fields present in the body change (no stale full-profile overwrite). */
function patch(db, user, body) {
  const sets = [];
  const args = [];
  for (const [field, [col, validate]] of Object.entries(EDITABLE)) {
    if (body[field] !== undefined) {
      sets.push(`${col} = ?`);
      args.push(validate(body[field]));
    }
  }
  if (!sets.length) throw new ApiError(400, 'VALIDATION_ERROR', 'No editable fields supplied');
  db.prepare(`UPDATE users SET ${sets.join(', ')}, updated_at = ? WHERE id = ?`).run(...args, Date.now(), user.id);
  return toDto(db.prepare('SELECT * FROM users WHERE id = ?').get(user.id));
}

function deleteAccount(db, user) {
  return db.tx(() => {
    if (rides.activeForUser(db, user)) throw new ApiError(409, 'ACTIVE_RIDE_EXISTS', 'Finish or cancel your active ride first');
    const w = ledger.getWallet(db, user.id);
    if (w.balancePaise > 0) {
      throw new ApiError(409, 'WALLET_NOT_EMPTY', 'Wallet balance must be withdrawn or refunded before deleting the account', { balancePaise: w.balancePaise });
    }
    db.prepare("UPDATE users SET deleted_at = ?, phone = NULL, name = '', email = '', emergency_contact_name = '', emergency_contact_phone = '', emergency_relationship = '' WHERE id = ?")
      .run(Date.now(), user.id);
    db.prepare('UPDATE sessions SET revoked = 1 WHERE user_id = ?').run(user.id);
    db.prepare('DELETE FROM addresses WHERE user_id = ?').run(user.id);
    // Ledger entries and ride records are retained (financial record keeping), detached from PII.
    return { deleted: true };
  });
}

function listAddresses(db, user) {
  return db.prepare('SELECT id addressId, label, name, subtitle, lat, lng, created_at createdAt FROM addresses WHERE user_id = ? ORDER BY created_at').all(user.id);
}

function addAddress(db, user, body) {
  const { lat, lng } = requireLatLng(body, 'address');
  const count = db.prepare('SELECT COUNT(*) c FROM addresses WHERE user_id = ?').get(user.id).c;
  if (count >= 20) throw new ApiError(409, 'ADDRESS_LIMIT', 'At most 20 saved addresses');
  const a = { addressId: id('adr'), label: requireString(body.label, 'label', { max: 40 }), name: requireString(body.name, 'name', { max: 200 }), subtitle: typeof body.subtitle === 'string' ? body.subtitle.slice(0, 300) : '', lat, lng, createdAt: Date.now() };
  db.prepare('INSERT INTO addresses (id, user_id, label, name, subtitle, lat, lng, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
    .run(a.addressId, user.id, a.label, a.name, a.subtitle, lat, lng, a.createdAt);
  return a;
}

function deleteAddress(db, user, addressId) {
  const res = db.prepare('DELETE FROM addresses WHERE id = ? AND user_id = ?').run(addressId, user.id);
  if (res.changes !== 1) throw new ApiError(404, 'NOT_FOUND', 'Address not found');
  return { deleted: true };
}

/**
 * SOS. No emergency-dispatch integration exists, so this records the event and reports honestly
 * that nothing was transmitted. The client must then direct the user to dial 112 themselves.
 */
function sos(db, user, body) {
  let point = null;
  try { point = requireLatLng(body, 'location'); } catch { point = null; }
  db.prepare("INSERT INTO sos_events (id, user_id, ride_id, lat, lng, outcome, created_at) VALUES (?, ?, ?, ?, ?, 'NOT_CONFIGURED', ?)")
    .run(id('sos'), user.id, typeof body.rideId === 'string' ? body.rideId : null, point?.lat ?? null, point?.lng ?? null, Date.now());
  throw new ApiError(503, 'SOS_NOT_CONFIGURED', 'Emergency dispatch integration is not configured. Call 112 directly.');
}

module.exports = { toDto, patch, deleteAccount, listAddresses, addAddress, deleteAddress, sos };
