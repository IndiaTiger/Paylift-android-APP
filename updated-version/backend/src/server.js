'use strict';
// PayLift test backend: HTTP routing. Zero runtime dependencies (Node >= 22.5, node:sqlite).
// See docs/API.md for the full contract.
const http = require('node:http');
const { ApiError, requireString, safeEqual } = require('./util');
const configLoader = require('./config');
const dbm = require('./db');
const auth = require('./auth');
const users = require('./users');
const ledger = require('./ledger');
const payments = require('./payments');
const rides = require('./rides');
const locations = require('./locations');
const pilots = require('./pilots');

const MAX_BODY = 64 * 1024;

function createApp(overrides = {}) {
  const cfg = { ...configLoader.load(overrides.env || process.env), ...(overrides.cfg || {}) };
  const db = dbm.open(overrides.dbFile || cfg.dbFile);
  if (overrides.seedDevPilots ?? cfg.devPilotSimulator) pilots.seedDevPilots(db);

  const routes = [];
  const route = (method, pattern, handler, opts = {}) => {
    const keys = [];
    const re = new RegExp('^' + pattern.replace(/\{(\w+)\}/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '$');
    routes.push({ method, re, keys, handler, auth: opts.auth !== false, admin: !!opts.admin });
  };

  const rideDto = (r, u) => rides.toDto(db, r, u);

  // Webhooks are delivered in-process by the test gateway (a real gateway POSTs them over HTTP).
  const deliverWebhook = (raw, sig) => setImmediate(() => {
    try { payments.webhook(db, cfg, raw, sig); } catch (e) { console.error('test webhook delivery failed', e.message); }
  });

  // ---- Health / config
  route('GET', '/health', () => ({ ok: true }), { auth: false });
  route('GET', '/config', () => ({
    authProvider: cfg.authProvider || null,
    paymentProvider: payments.adapterFor(cfg)?.name || null,
    mapsProvider: locations.adapterFor(cfg)?.name || null,
    sosAvailable: false,
    maskedCallAvailable: false,
    testMode: !cfg.isProd,
  }), { auth: false });

  // ---- Auth
  route('POST', '/auth/request-otp', ({ body }) => auth.requestOtp(db, cfg, body), { auth: false });
  route('POST', '/auth/verify-otp', ({ body }) => {
    const r = auth.verifyOtp(db, cfg, body);
    if (r.error) throw r.error;
    return { ...r.session, isNewUser: r.isNewUser, user: users.toDto(db.prepare('SELECT * FROM users WHERE id = ?').get(r.userId)) };
  }, { auth: false });
  route('POST', '/auth/refresh', ({ body }) => auth.refresh(db, cfg, body), { auth: false });
  route('POST', '/auth/logout', ({ body }) => auth.logout(db, body), { auth: false });

  // ---- Users
  route('GET', '/users/me', ({ user }) => users.toDto(user));
  route('PATCH', '/users/me', ({ user, body }) => users.patch(db, user, body));
  route('DELETE', '/users/me', ({ user }) => users.deleteAccount(db, user));
  route('GET', '/users/me/addresses', ({ user }) => ({ addresses: users.listAddresses(db, user) }));
  route('POST', '/users/me/addresses', ({ user, body }) => users.addAddress(db, user, body), { status: 201 });
  route('DELETE', '/users/me/addresses/{id}', ({ user, params }) => users.deleteAddress(db, user, params.id));

  // ---- Wallet (read-only for clients: there is no client endpoint that changes a balance)
  route('GET', '/wallet', ({ user }) => ledger.getWallet(db, user.id));
  route('GET', '/wallet/transactions', ({ user, query }) => ({
    transactions: ledger.listEntries(db, user.id, { limit: Math.min(Number(query.limit) || 50, 100), before: query.before ? Number(query.before) : undefined }),
  }));

  // ---- Payments
  route('POST', '/payments/orders', ({ user, body, req }) => payments.createOrder(db, cfg, user, body, req.headers['idempotency-key']));
  route('POST', '/payments/verify', ({ user, body }) => payments.verify(db, cfg, user, body));
  route('POST', '/payments/webhook', ({ raw, req }) => payments.webhook(db, cfg, raw, req.headers['x-paylift-signature']), { auth: false });
  route('GET', '/payments/{id}', ({ user, params }) => payments.toDto(payments.getOwned(db, user.id, params.id)));
  route('POST', '/payments/{id}/refund', ({ params, body }) => payments.refund(db, cfg, params.id, body), { auth: false, admin: true });
  route('POST', '/payments/test/checkout', ({ user, body }) => payments.testCheckout(db, cfg, user, body, deliverWebhook));

  // ---- Locations
  route('POST', '/locations/geocode', ({ body }) => locations.geocode(cfg, body));
  route('POST', '/locations/reverse-geocode', ({ body }) => locations.reverseGeocode(cfg, body));
  route('POST', '/routes', ({ body }) => locations.route(cfg, body));

  // ---- Rides
  route('POST', '/rides/quote', async ({ user, body }) => rides.quote(db, cfg, user, body));
  route('POST', '/rides', ({ user, body, req }) => rideDto(rides.request(db, cfg, user, body, req.headers['idempotency-key']), user), { status: 201 });
  route('GET', '/rides', ({ user, query }) => ({ rides: rides.listForRider(db, user, { limit: Math.min(Number(query.limit) || 50, 100) }).map((r) => rideDto(r, user)) }));
  route('GET', '/rides/active', ({ user }) => { const r = rides.activeForUser(db, user); return { ride: r ? rideDto(r, user) : null }; });
  route('GET', '/rides/{id}', ({ user, params }) => rideDto(rides.getVisible(db, user, params.id), user));
  route('POST', '/rides/{id}/cancel', ({ user, params, body }) => rideDto(rides.cancel(db, cfg, user, params.id, body), user));
  route('POST', '/rides/{id}/accept', ({ user, params }) => rideDto(rides.pilotAction(db, user, params.id, rides.S.PILOT_ACCEPTED), user));
  route('POST', '/rides/{id}/arriving', ({ user, params }) => rideDto(rides.pilotAction(db, user, params.id, rides.S.PILOT_ARRIVING), user));
  route('POST', '/rides/{id}/arrived', ({ user, params }) => rideDto(rides.pilotAction(db, user, params.id, rides.S.ARRIVED), user));
  route('POST', '/rides/{id}/verify-otp', ({ user, params, body }) => rideDto(rides.verifyStartOtp(db, cfg, user, params.id, body), user));
  route('POST', '/rides/{id}/start', ({ user, params }) => rideDto(rides.pilotAction(db, user, params.id, rides.S.IN_PROGRESS), user));
  route('POST', '/rides/{id}/complete', ({ user, params }) => rideDto(rides.complete(db, user, params.id), user));
  route('POST', '/rides/{id}/rating', ({ user, params, body }) => rideDto(rides.rate(db, user, params.id, body), user));
  route('GET', '/rides/{id}/messages', ({ user, params }) => ({ messages: rides.listMessages(db, user, params.id) }));
  route('POST', '/rides/{id}/messages', ({ user, params, body }) => rides.postMessage(db, user, params.id, body), { status: 201 });
  route('POST', '/rides/{id}/call', ({ user, params }) => {
    rides.getVisible(db, user, params.id);
    throw new ApiError(503, 'CALL_PROVIDER_NOT_CONFIGURED', 'Masked calling is not configured');
  });
  route('POST', '/rides/{id}/assign', ({ params, body }) => rideDto(rides.assign(db, params.id, requireString(body.pilotId, 'pilotId'), 'operator'), null), { auth: false, admin: true });
  route('POST', '/rides/{id}/fail', ({ params, body }) => rideDto(rides.transition(db, params.id, rides.S.FAILED, 'operator',
    { failure_reason: String(body.reason || 'OPERATOR').slice(0, 200) }, (before) => rides.settleUnfinished(db, cfg, before, { chargeFee: false })), null), { auth: false, admin: true });

  // ---- Pilots
  route('GET', '/pilots/nearby', ({ query }) => pilots.nearby(db, query));
  route('POST', '/pilots/me/location', ({ user, body }) => pilots.updateLocation(db, user, body));
  route('POST', '/pilots/me/availability', ({ user, body }) => pilots.setAvailability(db, user, body));

  // ---- Safety
  route('POST', '/safety/sos', ({ user, body }) => users.sos(db, user, body));

  async function handle(req, res) {
    const started = Date.now();
    const url = new URL(req.url, 'http://localhost');
    let status = 200;
    let payload;
    try {
      const match = routes.find((r) => r.method === req.method && r.re.test(url.pathname));
      if (!match) throw new ApiError(404, 'NOT_FOUND', 'Route not found');
      const m = match.re.exec(url.pathname);
      const params = Object.fromEntries(match.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])]));
      const raw = await readBody(req);
      let body = {};
      if (raw.length) {
        try { body = JSON.parse(raw); } catch { throw new ApiError(400, 'INVALID_JSON', 'Body must be JSON'); }
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw new ApiError(400, 'INVALID_JSON', 'Body must be a JSON object');
      }
      if (match.admin && (!cfg.adminToken || !safeEqual(req.headers['x-admin-token'] || '', cfg.adminToken))) {
        throw new ApiError(403, 'FORBIDDEN', 'Operator credentials required');
      }
      const user = match.auth ? auth.authenticate(db, cfg, req) : null;
      payload = await match.handler({ req, params, query: Object.fromEntries(url.searchParams), body, raw, user });
      if (req.method === 'POST' && /\/(rides|users\/me\/addresses|messages)$/.test(url.pathname)) status = 201;
    } catch (e) {
      if (e instanceof ApiError) {
        status = e.status;
        payload = { error: { code: e.code, message: e.message, ...(e.details ? { details: e.details } : {}) } };
      } else if (e && /UNIQUE constraint failed/.test(e.message)) {
        status = 409;
        payload = { error: { code: 'CONFLICT', message: 'Duplicate request' } };
      } else if (e && /CHECK constraint failed/.test(e.message)) {
        status = 409;
        payload = { error: { code: 'INVARIANT_VIOLATION', message: 'Operation rejected by ledger invariants' } };
      } else {
        console.error(e);
        status = 500;
        payload = { error: { code: 'INTERNAL', message: 'Internal error' } };
      }
    }
    const out = JSON.stringify(payload ?? {});
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    res.end(out);
    if (!overrides.quiet) console.log(`${req.method} ${url.pathname} ${status} ${Date.now() - started}ms`);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on('data', (c) => {
        size += c.length;
        if (size > MAX_BODY) { reject(new ApiError(413, 'BODY_TOO_LARGE', 'Body too large')); req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  const server = http.createServer((req, res) => { handle(req, res); });
  const timers = [setInterval(() => { try { rides.tick(db, cfg); } catch (e) { console.error('tick', e); } }, 2000)];
  if (cfg.devPilotSimulator) {
    timers.push(setInterval(() => { try { pilots.simulatorStep(db); } catch (e) { console.error('sim', e); } }, cfg.devSimStepMs));
  }
  timers.forEach((t) => t.unref());

  return {
    cfg, db, server,
    listen: (port = cfg.port, host = cfg.host) => new Promise((r) => server.listen(port, host, () => r(server.address()))),
    close: () => new Promise((r) => { timers.forEach(clearInterval); server.close(() => { db.close(); r(); }); }),
  };
}

if (require.main === module) {
  const app = createApp();
  app.cfg.warnings.forEach((w) => console.warn('WARN', w));
  app.listen().then((a) => console.log(`PayLift test backend listening on http://${a.address}:${a.port} (env=${app.cfg.nodeEnv}, auth=${app.cfg.authProvider || 'none'}, payments=${app.cfg.paymentProvider || 'none'}, maps=${app.cfg.mapsProvider || 'none'}, simulator=${app.cfg.devPilotSimulator})`));
}

module.exports = { createApp };
