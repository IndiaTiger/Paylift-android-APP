'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const fare = require('../src/fare');
const { startApp } = require('./helpers');

const VECTORS = path.join(__dirname, '..', '..', 'contracts', 'fare-test-vectors.json');

test('fare components sum exactly and splits are exact', () => {
  for (const vehicleId of Object.keys(fare.VEHICLES)) {
    for (const [m, min] of [[200, 1], [5200, 16], [12345, 37], [150000, 600]]) {
      const f = fare.calculate(vehicleId, m, min);
      assert.equal(f.subtotalPaise, f.baseFarePaise + f.transitChargePaise);
      assert.equal(f.taxablePaise, f.subtotalPaise + f.platformSafetyFeePaise);
      assert.equal(f.totalPaise, f.taxablePaise + f.gstPaise);
      assert.equal(f.pilotEarningPaise + f.platformCommissionPaise, f.taxablePaise);
      for (const v of Object.values(f)) if (typeof v === 'number') assert.ok(Number.isSafeInteger(v));
    }
  }
});

test('fare validation rejects out-of-range trips and unknown vehicles', () => {
  assert.throws(() => fare.calculate('cab_mini', 0, 5), /distance/);
  assert.throws(() => fare.calculate('cab_mini', 5000, 0), /duration/);
  assert.throws(() => fare.calculate('cab_mini', 5000.5, 10), /distance/);
  assert.throws(() => fare.calculate('rocket', 5000, 10), /Unknown vehicle/);
});

test('fare matches the shared contract vectors (also verified by the Android FareCalculator test)', () => {
  const vectors = JSON.parse(fs.readFileSync(VECTORS, 'utf8'));
  assert.ok(vectors.length >= 20);
  for (const v of vectors) {
    assert.deepEqual(fare.calculate(v.vehicleId, v.distanceMeters, v.durationMinutes), v.expected, JSON.stringify(v));
  }
});

test('location endpoints (dev adapter) label their data source', async () => {
  const t = await startApp();
  try {
    const s = await t.login('+919400000001');
    const g = await t.call('POST', '/locations/geocode', { token: s.accessToken, body: { query: 'airport' } });
    assert.equal(g.body.results[0].name, 'Jolly Grant Airport (DED)');
    assert.equal(g.body.results[0].source, 'dev-static');
    const rg = await t.call('POST', '/locations/reverse-geocode', { token: s.accessToken, body: { lat: 30.3256, lng: 78.0437 } });
    assert.equal(rg.body.name, 'Clock Tower (Ghanta Ghar)');
    const far = await t.call('POST', '/locations/reverse-geocode', { token: s.accessToken, body: { lat: 29.0, lng: 77.0 } });
    assert.equal(far.body.name, 'Pinned location');
    const r = await t.call('POST', '/routes', { token: s.accessToken, body: { origin: t.CLOCK_TOWER, destination: t.RAJPUR } });
    assert.equal(r.body.source, 'dev-approximation');
    assert.ok(r.body.distanceMeters > 3000 && r.body.distanceMeters < 15000);
    const bad = await t.call('POST', '/routes', { token: s.accessToken, body: { origin: { lat: 200, lng: 0 }, destination: t.RAJPUR } });
    assert.equal(bad.status, 400);
    const nearby = await t.call('GET', '/pilots/nearby?lat=30.3255&lng=78.0436&vehicleId=cab_mini', { token: s.accessToken });
    assert.equal(nearby.body.pilots.length, 1);
    assert.equal(nearby.body.pilots[0].name, undefined, 'riders do not get identities of unassigned pilots');
  } finally {
    await t.close();
  }
});

test('maps provider not configured -> 503 rather than fake data', async () => {
  const t = await startApp({ env: { MAPS_PROVIDER: '' } });
  try {
    const s = await t.login('+919400000002');
    const r = await t.call('POST', '/routes', { token: s.accessToken, body: { origin: t.CLOCK_TOWER, destination: t.RAJPUR } });
    assert.equal(r.status, 503);
    assert.equal(r.body.error.code, 'MAPS_PROVIDER_NOT_CONFIGURED');
  } finally {
    await t.close();
  }
});

test('polyline decoder handles the reference example', () => {
  const { decodePolyline } = require('../src/locations');
  assert.deepEqual(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@'), [
    { lat: 38.5, lng: -120.2 }, { lat: 40.7, lng: -120.95 }, { lat: 43.252, lng: -126.453 },
  ]);
});
