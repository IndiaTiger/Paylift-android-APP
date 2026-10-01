'use strict';
const crypto = require('node:crypto');

class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

const id = (prefix) => `${prefix}_${crypto.randomBytes(12).toString('hex')}`;
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const hmacHex = (secret, data) => crypto.createHmac('sha256', secret).update(data).digest('hex');

/** Constant-time comparison of two strings. */
function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

/** Integer division rounding half up (inputs must be non-negative safe integers). */
function roundDiv(numerator, denominator) {
  if (!Number.isSafeInteger(numerator) || !Number.isSafeInteger(denominator) || numerator < 0 || denominator <= 0) {
    throw new Error(`roundDiv: invalid operands ${numerator}/${denominator}`);
  }
  return Math.floor((2 * numerator + denominator) / (2 * denominator));
}

function requireInt(v, name, { min = -Infinity, max = Infinity } = {}) {
  if (!Number.isSafeInteger(v) || v < min || v > max) {
    throw new ApiError(400, 'VALIDATION_ERROR', `${name} must be an integer in [${min}, ${max}]`);
  }
  return v;
}

function requireString(v, name, { min = 1, max = 200, pattern } = {}) {
  if (typeof v !== 'string' || v.trim().length < min || v.length > max || (pattern && !pattern.test(v))) {
    throw new ApiError(400, 'VALIDATION_ERROR', `${name} is invalid`);
  }
  return v.trim();
}

function requireLatLng(p, name) {
  if (!p || typeof p !== 'object') throw new ApiError(400, 'VALIDATION_ERROR', `${name} is required`);
  const { lat, lng } = p;
  if (typeof lat !== 'number' || typeof lng !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lng) ||
      lat < -90 || lat > 90 || lng < -180 || lng > 180) {
    throw new ApiError(400, 'VALIDATION_ERROR', `${name} has invalid coordinates`);
  }
  return { lat, lng };
}

/** Great-circle distance in metres. */
function haversineMeters(a, b) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

module.exports = { ApiError, id, sha256, hmacHex, safeEqual, roundDiv, requireInt, requireString, requireLatLng, haversineMeters };
