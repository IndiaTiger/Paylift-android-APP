'use strict';
// Pilot availability / location and the development pilot simulator.
const { ApiError, requireLatLng, haversineMeters, id } = require('./util');
const rides = require('./rides');

function nearby(db, query) {
  const lat = Number(query.lat);
  const lng = Number(query.lng);
  const point = requireLatLng({ lat, lng }, 'point');
  const vehicleId = typeof query.vehicleId === 'string' ? query.vehicleId : null;
  const radius = Math.min(Number(query.radiusMeters) || 5000, 20000);
  const list = db.prepare("SELECT * FROM pilots WHERE status = 'AVAILABLE'").all()
    .filter((p) => !vehicleId || p.vehicle_ids.split(',').includes(vehicleId))
    .map((p) => ({ p, d: haversineMeters(point, p) }))
    .filter((x) => x.d <= radius)
    .sort((a, b) => a.d - b.d)
    .slice(0, 20);
  // Riders only get coarse information about pilots that are not assigned to them.
  return {
    pilots: list.map(({ p, d }) => ({
      pilotId: p.id,
      vehicleIds: p.vehicle_ids.split(','),
      location: { lat: Number(p.lat.toFixed(3)), lng: Number(p.lng.toFixed(3)) },
      distanceMeters: Math.round(d),
      etaMinutes: Math.max(1, Math.round(d / 1000 / 20 * 60)),
    })),
  };
}

function requirePilot(db, user) {
  const pilot = rides.pilotForUser(db, user);
  if (!pilot) throw new ApiError(403, 'FORBIDDEN', 'Pilot account required');
  return pilot;
}

function updateLocation(db, user, body) {
  const pilot = requirePilot(db, user);
  const { lat, lng } = requireLatLng(body, 'location');
  db.prepare('UPDATE pilots SET lat = ?, lng = ?, location_updated_at = ? WHERE id = ?').run(lat, lng, Date.now(), pilot.id);
  return { ok: true };
}

function setAvailability(db, user, body) {
  const pilot = requirePilot(db, user);
  const status = body.status;
  if (!['AVAILABLE', 'OFFLINE'].includes(status)) throw new ApiError(400, 'VALIDATION_ERROR', 'status must be AVAILABLE or OFFLINE');
  // A pilot on an active ride stays BUSY until the ride ends.
  const res = db.prepare("UPDATE pilots SET status = ? WHERE id = ? AND status != 'BUSY'").run(status, pilot.id);
  if (res.changes !== 1) throw new ApiError(409, 'PILOT_BUSY', 'Cannot change availability during a ride');
  return { ok: true, status };
}

// ------------------------------------------------------------------------------------------
// Development data + simulator (only used when DEV_PILOT_SIMULATOR=true / seeding enabled).

const DEV_PILOTS = [
  { name: 'Vikram Singh Rawat', rating: 4.92, trips: 1840, vehicles: 'cab_mini,cab_sedan', model: 'Swift Dzire Prime', color: 'Arctic White', plate: 'UK07-BX-4921', initials: 'VR', phone: '+910000000101', lat: 30.3290, lng: 78.0480 },
  { name: 'Aman Negi', rating: 4.88, trips: 960, vehicles: 'moto_commuter,moto_sports', model: 'Hero Splendor Pro', color: 'Jet Black', plate: 'UK07-CM-1088', initials: 'AN', phone: '+910000000102', lat: 30.3230, lng: 78.0400 },
  { name: 'Deepak Joshi', rating: 4.95, trips: 2410, vehicles: 'cab_suv,cab_luxury', model: 'Toyota Innova Crysta', color: 'Silver Metallic', plate: 'UK07-TA-9930', initials: 'DJ', phone: '+910000000103', lat: 30.3310, lng: 78.0510 },
  { name: 'Rahul Chauhan', rating: 4.90, trips: 1150, vehicles: 'moto_electric', model: 'Ola S1 Pro (EV)', color: 'Electric Blue', plate: 'UK07-EV-5512', initials: 'RC', phone: '+910000000104', lat: 30.3200, lng: 78.0450 },
];

function seedDevPilots(db) {
  db.tx(() => {
    for (const p of DEV_PILOTS) {
      if (db.prepare('SELECT 1 FROM users WHERE phone = ?').get(p.phone)) continue;
      const now = Date.now();
      const userId = id('usr');
      db.prepare("INSERT INTO users (id, phone, role, name, created_at, updated_at) VALUES (?, ?, 'pilot', ?, ?, ?)").run(userId, p.phone, p.name, now, now);
      db.prepare(`INSERT INTO pilots (id, user_id, name, rating, total_trips, vehicle_ids, vehicle_model, vehicle_color, license_plate, avatar_initials, status, lat, lng, location_updated_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?, ?)`)
        .run(id('plt'), userId, p.name, p.rating, p.trips, p.vehicles, p.model, p.color, p.plate, p.initials, p.lat, p.lng, now);
    }
  });
}

/**
 * Drives assigned rides through the pilot-side lifecycle using the SAME guarded transitions a
 * real pilot app would call. Development only: it stands in for pilots so the rider app can be
 * exercised end to end without a pilot device.
 */
function simulatorStep(db) {
  const active = db.prepare(`SELECT * FROM rides WHERE pilot_id IS NOT NULL AND status IN
    ('ASSIGNED','PILOT_ACCEPTED','PILOT_ARRIVING','ARRIVED','OTP_VERIFIED','IN_PROGRESS')`).all();
  for (const ride of active) {
    const pilot = db.prepare('SELECT * FROM pilots WHERE id = ?').get(ride.pilot_id);
    const move = (pt) => db.prepare('UPDATE pilots SET lat = ?, lng = ?, location_updated_at = ? WHERE id = ?').run(pt.lat, pt.lng, Date.now(), pilot.id);
    const pickup = JSON.parse(ride.pickup_json);
    try {
      switch (ride.status) {
        case 'ASSIGNED': rides.transition(db, ride.id, 'PILOT_ACCEPTED', 'dev-simulator'); break;
        case 'PILOT_ACCEPTED': rides.transition(db, ride.id, 'PILOT_ARRIVING', 'dev-simulator'); break;
        case 'PILOT_ARRIVING': move(pickup); rides.transition(db, ride.id, 'ARRIVED', 'dev-simulator'); break;
        case 'ARRIVED': rides.transition(db, ride.id, 'OTP_VERIFIED', 'dev-simulator'); break;
        case 'OTP_VERIFIED': rides.transition(db, ride.id, 'IN_PROGRESS', 'dev-simulator'); break;
        case 'IN_PROGRESS': {
          const poly = JSON.parse(ride.route_json).polyline;
          const idx = Math.round(rides.toDto(db, ride, null).progressFraction * (poly.length - 1));
          const next = Math.min(idx + Math.ceil(poly.length / 6), poly.length - 1);
          move(poly[next]);
          if (next === poly.length - 1) rides.complete(db, null, ride.id, 'dev-simulator');
          break;
        }
        default: break;
      }
    } catch (e) {
      if (!(e instanceof ApiError)) throw e; // a rider cancel won the race - fine
    }
  }
}

module.exports = { nearby, updateLocation, setAvailability, seedDevPilots, simulatorStep, DEV_PILOTS };
