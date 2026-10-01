'use strict';
// SQLite persistence for the PayLift test backend.
//
// Money is always stored as INTEGER paise. Every balance-changing operation runs inside
// `tx()` (BEGIN IMMEDIATE), and the invariants that matter for money are enforced by the
// database itself (CHECK / UNIQUE / triggers) so that a code bug cannot silently violate them.

const { DatabaseSync } = require('node:sqlite');
const fs = require('node:fs');
const path = require('node:path');

const ACTIVE_RIDE_STATUSES = [
  'REQUESTED', 'SEARCHING', 'ASSIGNED', 'PILOT_ACCEPTED', 'PILOT_ARRIVING', 'ARRIVED',
  'OTP_VERIFIED', 'IN_PROGRESS',
];

const SCHEMA = `
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  phone TEXT UNIQUE,
  role TEXT NOT NULL DEFAULT 'rider' CHECK (role IN ('rider','pilot')),
  name TEXT NOT NULL DEFAULT '',
  email TEXT NOT NULL DEFAULT '',
  emergency_contact_name TEXT NOT NULL DEFAULT '',
  emergency_contact_phone TEXT NOT NULL DEFAULT '',
  emergency_relationship TEXT NOT NULL DEFAULT '',
  auto_dial_sos INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE IF NOT EXISTS otp_requests (
  id TEXT PRIMARY KEY,
  phone TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  consumed INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_otp_phone ON otp_requests(phone, created_at);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  family_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS addresses (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  label TEXT NOT NULL,
  name TEXT NOT NULL,
  subtitle TEXT NOT NULL DEFAULT '',
  lat REAL NOT NULL,
  lng REAL NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_addresses_user ON addresses(user_id);

-- Wallet: balance = settled funds, held = funds reserved for active rides.
-- available = balance - held. Both can never go negative and held can never exceed balance.
CREATE TABLE IF NOT EXISTS wallets (
  user_id TEXT PRIMARY KEY REFERENCES users(id),
  balance_paise INTEGER NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
  held_paise INTEGER NOT NULL DEFAULT 0 CHECK (held_paise >= 0),
  currency TEXT NOT NULL DEFAULT 'INR',
  updated_at INTEGER NOT NULL,
  CHECK (held_paise <= balance_paise)
);

-- Immutable double-entry style ledger. "reference" is the natural idempotency key of the
-- business event (e.g. topup:<paymentId>, hold:<rideId>) and is UNIQUE, so the same event can
-- never be applied twice even under concurrent retries.
CREATE TABLE IF NOT EXISTS ledger_entries (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  type TEXT NOT NULL CHECK (type IN ('TOPUP_CREDIT','RIDE_HOLD','HOLD_RELEASE','RIDE_CAPTURE',
                                      'CANCELLATION_FEE','RIDE_REFUND','TOPUP_REFUND')),
  amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
  balance_after_paise INTEGER NOT NULL,
  held_after_paise INTEGER NOT NULL,
  reference TEXT NOT NULL UNIQUE,
  payment_id TEXT REFERENCES payments(id),
  ride_id TEXT REFERENCES rides(id),
  related_entry_id TEXT REFERENCES ledger_entries(id),
  description TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ledger_user ON ledger_entries(user_id, created_at);
CREATE TRIGGER IF NOT EXISTS ledger_no_update BEFORE UPDATE ON ledger_entries
  BEGIN SELECT RAISE(ABORT, 'ledger entries are immutable'); END;
CREATE TRIGGER IF NOT EXISTS ledger_no_delete BEFORE DELETE ON ledger_entries
  BEGIN SELECT RAISE(ABORT, 'ledger entries are immutable'); END;

CREATE TABLE IF NOT EXISTS payments (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  provider TEXT NOT NULL,
  provider_order_id TEXT NOT NULL UNIQUE,
  provider_payment_id TEXT UNIQUE,
  amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
  refunded_paise INTEGER NOT NULL DEFAULT 0 CHECK (refunded_paise >= 0),
  currency TEXT NOT NULL DEFAULT 'INR',
  status TEXT NOT NULL CHECK (status IN ('PENDING','SUCCESS','FAILED','CANCELLED','REFUNDED')),
  failure_reason TEXT,
  idempotency_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE (user_id, idempotency_key),
  CHECK (refunded_paise <= amount_paise)
);

CREATE TABLE IF NOT EXISTS webhook_events (
  event_id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  type TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  outcome TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS quotes (
  id TEXT PRIMARY KEY,
  rider_id TEXT NOT NULL REFERENCES users(id),
  vehicle_id TEXT NOT NULL,
  pickup_json TEXT NOT NULL,
  dropoff_json TEXT NOT NULL,
  distance_m INTEGER NOT NULL,
  duration_min INTEGER NOT NULL,
  route_json TEXT NOT NULL,
  fare_json TEXT NOT NULL,
  total_paise INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pilots (
  id TEXT PRIMARY KEY,
  user_id TEXT UNIQUE REFERENCES users(id),
  name TEXT NOT NULL,
  rating REAL NOT NULL,
  total_trips INTEGER NOT NULL,
  vehicle_ids TEXT NOT NULL,
  vehicle_model TEXT NOT NULL,
  vehicle_color TEXT NOT NULL,
  license_plate TEXT NOT NULL,
  avatar_initials TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('OFFLINE','AVAILABLE','BUSY')),
  lat REAL NOT NULL,
  lng REAL NOT NULL,
  location_updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rides (
  id TEXT PRIMARY KEY,
  rider_id TEXT NOT NULL REFERENCES users(id),
  pilot_id TEXT REFERENCES pilots(id),
  quote_id TEXT NOT NULL UNIQUE REFERENCES quotes(id),
  vehicle_id TEXT NOT NULL,
  status TEXT NOT NULL,
  pickup_json TEXT NOT NULL,
  dropoff_json TEXT NOT NULL,
  distance_m INTEGER NOT NULL,
  duration_min INTEGER NOT NULL,
  route_json TEXT NOT NULL,
  fare_json TEXT NOT NULL,
  fare_total_paise INTEGER NOT NULL CHECK (fare_total_paise > 0),
  charged_paise INTEGER NOT NULL DEFAULT 0,
  start_otp TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  cancel_reason TEXT,
  cancelled_by TEXT,
  failure_reason TEXT,
  rating_stars INTEGER CHECK (rating_stars BETWEEN 1 AND 5),
  rating_tags TEXT,
  otp_failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (otp_failed_attempts >= 0),
  otp_locked_until INTEGER,
  version INTEGER NOT NULL DEFAULT 0,
  search_started_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  completed_at INTEGER,
  UNIQUE (rider_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_rides_rider ON rides(rider_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rides_pilot ON rides(pilot_id);
-- At most one active ride per rider and per pilot (prevents double-request races).
CREATE UNIQUE INDEX IF NOT EXISTS uniq_active_ride_rider ON rides(rider_id)
  WHERE status IN (${ACTIVE_RIDE_STATUSES.map((s) => `'${s}'`).join(',')});
CREATE UNIQUE INDEX IF NOT EXISTS uniq_active_ride_pilot ON rides(pilot_id)
  WHERE pilot_id IS NOT NULL AND status IN (${ACTIVE_RIDE_STATUSES.filter((s) => !['REQUESTED', 'SEARCHING'].includes(s)).map((s) => `'${s}'`).join(',')});

CREATE TABLE IF NOT EXISTS ride_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ride_id TEXT NOT NULL REFERENCES rides(id),
  from_status TEXT,
  to_status TEXT NOT NULL,
  actor TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ride_messages (
  id TEXT PRIMARY KEY,
  ride_id TEXT NOT NULL REFERENCES rides(id),
  sender_role TEXT NOT NULL CHECK (sender_role IN ('rider','pilot','system')),
  body TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_ride ON ride_messages(ride_id, created_at);

CREATE TABLE IF NOT EXISTS idempotency_records (
  user_id TEXT NOT NULL,
  key TEXT NOT NULL,
  route TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  status_code INTEGER NOT NULL,
  response_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, key, route)
);

CREATE TABLE IF NOT EXISTS sos_events (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  ride_id TEXT,
  lat REAL,
  lng REAL,
  outcome TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
`;

function open(file) {
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode = WAL;');
  db.exec('PRAGMA busy_timeout = 5000;');
  db.exec(SCHEMA);
  // Additive column migration for databases created before the start-OTP attempt limit.
  const rideCols = db.prepare('PRAGMA table_info(rides)').all().map((c) => c.name);
  if (!rideCols.includes('otp_failed_attempts')) db.exec('ALTER TABLE rides ADD COLUMN otp_failed_attempts INTEGER NOT NULL DEFAULT 0');
  if (!rideCols.includes('otp_locked_until')) db.exec('ALTER TABLE rides ADD COLUMN otp_locked_until INTEGER');
  let depth = 0;
  /** Runs fn inside a single IMMEDIATE transaction (nested calls join the outer one). */
  db.tx = (fn) => {
    if (depth > 0) return fn();
    db.exec('BEGIN IMMEDIATE');
    depth++;
    try {
      const result = fn();
      depth--;
      db.exec('COMMIT');
      return result;
    } catch (e) {
      depth--;
      db.exec('ROLLBACK');
      throw e;
    }
  };
  return db;
}

module.exports = { open, ACTIVE_RIDE_STATUSES };
