'use strict';
// Server configuration. All secrets come from the environment (see backend/.env.example).
// Nothing secret is hard-coded; for local test runs ephemeral random secrets are generated
// per process and a warning is recorded.
const crypto = require('node:crypto');

const TEST_ONLY = { authProvider: 'dev', paymentProvider: 'test', mapsProvider: 'dev' };

function load(env = process.env) {
  const nodeEnv = env.NODE_ENV || 'development';
  const isProd = nodeEnv === 'production';
  const warnings = [];
  const secret = (name) => {
    if (env[name]) return env[name];
    if (isProd) throw new Error(`${name} must be set in production`);
    warnings.push(`${name} not set - using an ephemeral random value (test/dev only)`);
    return crypto.randomBytes(32).toString('hex');
  };
  const cfg = {
    nodeEnv,
    isProd,
    port: Number(env.PORT || 8080),
    host: env.HOST || '127.0.0.1',
    dbFile: env.DATABASE_FILE || 'data/paylift-test.db',
    jwtSecret: secret('JWT_SECRET'),
    otpPepper: secret('OTP_PEPPER'),
    accessTokenTtlSec: Number(env.ACCESS_TOKEN_TTL_SEC || 900),
    refreshTokenTtlSec: Number(env.REFRESH_TOKEN_TTL_SEC || 30 * 24 * 3600),
    authProvider: (env.AUTH_PROVIDER || '').toLowerCase(),
    // Demo login (presentations): a fixed phone + code, honoured ONLY by the dev OTP adapter.
    demoLoginPhone: env.DEMO_LOGIN_PHONE || '',
    demoLoginOtp: env.DEMO_LOGIN_OTP || '',
    paymentProvider: (env.PAYMENT_PROVIDER || '').toLowerCase(),
    paymentKeyId: env.PAYMENT_KEY_ID || '',
    paymentKeySecret: env.PAYMENT_KEY_SECRET || '',
    paymentWebhookSecret: env.PAYMENT_WEBHOOK_SECRET || '',
    mapsProvider: (env.MAPS_PROVIDER || '').toLowerCase(),
    mapsServerKey: env.MAPS_SERVER_KEY || '',
    adminToken: env.ADMIN_API_TOKEN || '',
    devPilotSimulator: env.DEV_PILOT_SIMULATOR === 'true',
    devSimStepMs: Number(env.DEV_SIM_STEP_MS || 4000),
    searchTimeoutSec: Number(env.SEARCH_TIMEOUT_SEC || 120),
    quoteTtlSec: Number(env.QUOTE_TTL_SEC || 300),
    startOtpMaxAttempts: Number(env.START_OTP_MAX_ATTEMPTS || 5),
    startOtpLockSec: Number(env.START_OTP_LOCK_SEC || 300),
    cancellationFeePaise: Number(env.CANCELLATION_FEE_PAISE || 0),
    minTopupPaise: Number(env.MIN_TOPUP_PAISE || 100),
    maxTopupPaise: Number(env.MAX_TOPUP_PAISE || 1000000),
    warnings,
  };
  for (const [key, testValue] of Object.entries(TEST_ONLY)) {
    if (isProd && cfg[key] === testValue) throw new Error(`${key}=${testValue} is a test adapter and is forbidden in production`);
  }
  if (isProd && (cfg.demoLoginPhone || cfg.demoLoginOtp)) throw new Error('DEMO_LOGIN_* is forbidden in production');
  if (cfg.demoLoginOtp && !/^\d{6}$/.test(cfg.demoLoginOtp)) throw new Error('DEMO_LOGIN_OTP must be 6 digits');
  if (isProd && cfg.devPilotSimulator) throw new Error('DEV_PILOT_SIMULATOR is forbidden in production');
  if (cfg.paymentProvider === 'test') {
    // Test gateway credentials are generated per process: they are never real and never persisted.
    cfg.paymentKeySecret = cfg.paymentKeySecret || crypto.randomBytes(32).toString('hex');
    cfg.paymentWebhookSecret = cfg.paymentWebhookSecret || crypto.randomBytes(32).toString('hex');
    cfg.paymentKeyId = cfg.paymentKeyId || 'test_key_public';
  }
  return cfg;
}

module.exports = { load };
