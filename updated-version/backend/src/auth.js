'use strict';
// Phone OTP authentication, short-lived access tokens (HS256 JWT) and rotating refresh tokens.
//
// OTP delivery is behind an adapter. The only adapter implemented here is `dev`, which returns
// the code in the API response so automated tests can log in; it is refused in production
// (see config.js). A real SMS/verify provider must be plugged into `otpSenders`.
const crypto = require('node:crypto');
const { ApiError, id, sha256, hmacHex, safeEqual, requireString } = require('./util');
const ledger = require('./ledger');

const PHONE_RE = /^\+[1-9]\d{7,14}$/;
const OTP_TTL_MS = 5 * 60 * 1000;
const OTP_MAX_ATTEMPTS = 5;
const OTP_MAX_REQUESTS_PER_WINDOW = 5;
const OTP_WINDOW_MS = 15 * 60 * 1000;

const b64url = (buf) => Buffer.from(buf).toString('base64url');

function signJwt(cfg, payload) {
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify(payload));
  const sig = crypto.createHmac('sha256', cfg.jwtSecret).update(`${header}.${body}`).digest('base64url');
  return `${header}.${body}.${sig}`;
}

function verifyJwt(cfg, token) {
  const parts = typeof token === 'string' ? token.split('.') : [];
  if (parts.length !== 3) return null;
  const expected = crypto.createHmac('sha256', cfg.jwtSecret).update(`${parts[0]}.${parts[1]}`).digest('base64url');
  if (!safeEqual(expected, parts[2])) return null;
  let header;
  let payload;
  try {
    header = JSON.parse(Buffer.from(parts[0], 'base64url').toString());
    payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
  } catch {
    return null;
  }
  if (header.alg !== 'HS256') return null;
  if (typeof payload.exp !== 'number' || payload.exp * 1000 < Date.now()) return null;
  return payload;
}

const otpSenders = {
  // Development/test only: the code is returned to the caller. Never enabled in production.
  dev: (_phone, code) => ({ devOtp: code }),
};

function normalizePhone(phone) {
  const p = requireString(phone, 'phone', { max: 20 }).replace(/[\s-]/g, '');
  if (!PHONE_RE.test(p)) throw new ApiError(400, 'INVALID_PHONE', 'Phone must be in E.164 format, e.g. +919876543210');
  return p;
}

function requestOtp(db, cfg, body) {
  const sender = otpSenders[cfg.authProvider];
  if (!sender) throw new ApiError(503, 'AUTH_PROVIDER_NOT_CONFIGURED', 'OTP delivery provider is not configured');
  const phone = normalizePhone(body.phone);
  const recent = db.prepare('SELECT COUNT(*) c FROM otp_requests WHERE phone = ? AND created_at > ?').get(phone, Date.now() - OTP_WINDOW_MS).c;
  if (recent >= OTP_MAX_REQUESTS_PER_WINDOW) throw new ApiError(429, 'OTP_RATE_LIMITED', 'Too many OTP requests, try again later');
  // Demo login: only the dev adapter (never production, see config.js) uses the fixed demo code,
  // and only for the configured demo phone. Every other protection (rate limit, expiry,
  // single use, attempt limit) still applies.
  const isDemo = cfg.authProvider === 'dev' && !cfg.isProd && cfg.demoLoginPhone && cfg.demoLoginOtp &&
    phone === normalizePhone(cfg.demoLoginPhone);
  const code = isDemo ? cfg.demoLoginOtp : String(crypto.randomInt(0, 1000000)).padStart(6, '0');
  const requestId = id('otp');
  db.prepare('INSERT INTO otp_requests (id, phone, code_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)')
    .run(requestId, phone, hmacHex(cfg.otpPepper, `${requestId}:${code}`), Date.now() + OTP_TTL_MS, Date.now());
  return { requestId, expiresInSec: OTP_TTL_MS / 1000, ...sender(phone, code) };
}

function issueSession(db, cfg, user, familyId = id('fam')) {
  const now = Math.floor(Date.now() / 1000);
  const accessToken = signJwt(cfg, { sub: user.id, role: user.role, iat: now, exp: now + cfg.accessTokenTtlSec, jti: id('at') });
  const refreshToken = crypto.randomBytes(32).toString('base64url');
  db.prepare('INSERT INTO sessions (token_hash, user_id, family_id, expires_at, created_at) VALUES (?, ?, ?, ?, ?)')
    .run(sha256(refreshToken), user.id, familyId, Date.now() + cfg.refreshTokenTtlSec * 1000, Date.now());
  return { accessToken, refreshToken, tokenType: 'Bearer', expiresInSec: cfg.accessTokenTtlSec };
}

function verifyOtp(db, cfg, body) {
  const phone = normalizePhone(body.phone);
  const requestId = requireString(body.requestId, 'requestId', { max: 64 });
  const code = requireString(body.otp, 'otp', { min: 6, max: 6, pattern: /^\d{6}$/ });
  return db.tx(() => {
    const req = db.prepare('SELECT * FROM otp_requests WHERE id = ? AND phone = ?').get(requestId, phone);
    if (!req || req.consumed || req.expires_at < Date.now()) throw new ApiError(401, 'OTP_EXPIRED', 'OTP expired or already used');
    if (req.attempts >= OTP_MAX_ATTEMPTS) throw new ApiError(429, 'OTP_LOCKED', 'Too many wrong attempts');
    if (!safeEqual(hmacHex(cfg.otpPepper, `${requestId}:${code}`), req.code_hash)) {
      db.prepare('UPDATE otp_requests SET attempts = attempts + 1 WHERE id = ?').run(requestId);
      return { error: new ApiError(401, 'OTP_INVALID', 'Incorrect OTP') };
    }
    db.prepare('UPDATE otp_requests SET consumed = 1 WHERE id = ?').run(requestId);
    let user = db.prepare('SELECT * FROM users WHERE phone = ? AND deleted_at IS NULL').get(phone);
    let isNewUser = false;
    if (!user) {
      isNewUser = true;
      const now = Date.now();
      user = { id: id('usr'), phone, role: 'rider' };
      db.prepare('INSERT INTO users (id, phone, role, created_at, updated_at) VALUES (?, ?, ?, ?, ?)').run(user.id, phone, 'rider', now, now);
      ledger.ensureWallet(db, user.id); // new wallets start at exactly 0
    }
    return { session: issueSession(db, cfg, user), userId: user.id, isNewUser };
  });
}

function refresh(db, cfg, body) {
  const token = requireString(body.refreshToken, 'refreshToken', { max: 200 });
  const result = db.tx(() => {
    const s = db.prepare('SELECT * FROM sessions WHERE token_hash = ?').get(sha256(token));
    if (!s) throw new ApiError(401, 'INVALID_REFRESH_TOKEN', 'Invalid refresh token');
    if (s.revoked) {
      // Reuse of a rotated token: assume theft and revoke the whole family.
      // Committed before the error is raised (throwing inside tx would roll the revocation back).
      db.prepare('UPDATE sessions SET revoked = 1 WHERE family_id = ?').run(s.family_id);
      return { reused: true };
    }
    if (s.expires_at < Date.now()) throw new ApiError(401, 'INVALID_REFRESH_TOKEN', 'Refresh token expired');
    const user = db.prepare('SELECT * FROM users WHERE id = ? AND deleted_at IS NULL').get(s.user_id);
    if (!user) throw new ApiError(401, 'INVALID_REFRESH_TOKEN', 'Account no longer exists');
    db.prepare('UPDATE sessions SET revoked = 1 WHERE token_hash = ?').run(s.token_hash);
    return issueSession(db, cfg, user, s.family_id);
  });
  if (result.reused) throw new ApiError(401, 'REFRESH_TOKEN_REUSED', 'Refresh token reuse detected; please sign in again');
  return result;
}

function logout(db, body) {
  if (typeof body.refreshToken === 'string') {
    db.prepare('UPDATE sessions SET revoked = 1 WHERE token_hash = ?').run(sha256(body.refreshToken));
  }
  return { ok: true };
}

/** Resolves the Authorization header to a live user, or throws 401. */
function authenticate(db, cfg, req) {
  const h = req.headers.authorization || '';
  const m = /^Bearer (.+)$/.exec(h);
  const claims = m && verifyJwt(cfg, m[1]);
  if (!claims) throw new ApiError(401, 'UNAUTHENTICATED', 'Missing or invalid access token');
  const user = db.prepare('SELECT * FROM users WHERE id = ? AND deleted_at IS NULL').get(claims.sub);
  if (!user) throw new ApiError(401, 'UNAUTHENTICATED', 'Account no longer exists');
  return user;
}

module.exports = { requestOtp, verifyOtp, refresh, logout, authenticate, issueSession, signJwt, verifyJwt, normalizePhone };
