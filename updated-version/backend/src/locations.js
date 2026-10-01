'use strict';
// Location service: geocoding, reverse geocoding and routing behind a provider adapter.
//
//   dev    : static Dehradun place list + great-circle x winding-factor routing. Results are
//            labelled source="dev-*" so nobody can mistake them for real road data.
//            Refused in production (config.js).
//   google : Google Geocoding API + Routes API using MAPS_SERVER_KEY (server-side key).
//            Implemented against the public API documentation but NOT verified in this
//            repository because no key was available.
const { ApiError, requireLatLng, requireString, haversineMeters } = require('./util');

const DEV_PLACES = [
  { name: 'Clock Tower (Ghanta Ghar)', subtitle: 'City Center, Rajpur Road, Dehradun', lat: 30.3255, lng: 78.0436, category: 'City Center' },
  { name: 'Rajpur Road (Pacific Mall)', subtitle: 'Jakhan, Rajpur Road, Dehradun', lat: 30.3580, lng: 78.0720, category: 'Commercial' },
  { name: 'ISBT Dehradun', subtitle: 'Inter-State Bus Terminal, Haridwar Bypass', lat: 30.2870, lng: 78.0060, category: 'Transit' },
  { name: 'Prem Nagar (Chakrata Road)', subtitle: 'Near Graphic Era & IMA, Dehradun', lat: 30.3340, lng: 77.9620, category: 'Institutional' },
  { name: 'Jolly Grant Airport (DED)', subtitle: 'Dehradun Airport, Rishikesh Highway', lat: 30.1900, lng: 78.1800, category: 'Airport' },
  { name: 'Sahastradhara Springs', subtitle: 'Sulphur Springs & Ropeway, Sahastradhara Road', lat: 30.3872, lng: 78.1288, category: 'Tourist' },
  { name: 'Rispana Pull (Bypass)', subtitle: 'Haridwar-Dehradun Highway Intersection', lat: 30.3015, lng: 78.0645, category: 'Transit' },
  { name: 'Ballupur Chowk', subtitle: 'GMS Road / Kaulagarh Junction, Dehradun', lat: 30.3370, lng: 78.0160, category: 'Junction' },
  { name: 'Forest Research Institute (FRI)', subtitle: 'Chakrata Road, Kaulagarh, Dehradun', lat: 30.3426, lng: 77.9995, category: 'Heritage' },
  { name: 'Paltan Bazaar', subtitle: 'Near Dehradun Railway Station, City Heart', lat: 30.3210, lng: 78.0410, category: 'Market' },
  { name: 'Mussoorie Diversion (Malsi)', subtitle: 'Rajpur Foothills & Zoo, Mussoorie Highway', lat: 30.3810, lng: 78.0850, category: 'Foothills' },
];

const MIN_TRIP_METERS = 200;

const devAdapter = {
  name: 'dev',
  async geocode(query) {
    const q = query.toLowerCase();
    return DEV_PLACES.filter((p) => !q || p.name.toLowerCase().includes(q) || p.subtitle.toLowerCase().includes(q))
      .map((p) => ({ ...p, source: 'dev-static' }));
  },
  async reverseGeocode(point) {
    let best = null;
    for (const p of DEV_PLACES) {
      const d = haversineMeters(point, p);
      if (!best || d < best.d) best = { p, d };
    }
    if (best && best.d <= 500) return { ...best.p, lat: point.lat, lng: point.lng, source: 'dev-static' };
    return { name: `Pinned location`, subtitle: `${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}`, lat: point.lat, lng: point.lng, category: 'Pin', source: 'dev-static' };
  },
  async route(origin, destination) {
    const straight = haversineMeters(origin, destination);
    const distanceMeters = Math.max(Math.round(straight * 1.28), MIN_TRIP_METERS);
    const durationMinutes = Math.max(Math.floor((distanceMeters / 1000 / 24) * 60) + 3, 5);
    const steps = 24;
    const polyline = [];
    for (let i = 0; i <= steps; i++) {
      const f = i / steps;
      polyline.push({ lat: origin.lat + (destination.lat - origin.lat) * f, lng: origin.lng + (destination.lng - origin.lng) * f });
    }
    return { distanceMeters, durationMinutes, polyline, source: 'dev-approximation' };
  },
};

function decodePolyline(str) {
  const points = [];
  let index = 0;
  let lat = 0;
  let lng = 0;
  while (index < str.length) {
    for (const which of ['lat', 'lng']) {
      let result = 0;
      let shift = 0;
      let b;
      do {
        b = str.charCodeAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (which === 'lat') lat += delta; else lng += delta;
    }
    points.push({ lat: lat / 1e5, lng: lng / 1e5 });
  }
  return points;
}

function googleAdapter(key) {
  const get = async (url) => {
    const r = await fetch(url);
    if (!r.ok) throw new ApiError(502, 'MAPS_UPSTREAM_ERROR', `Maps provider HTTP ${r.status}`);
    return r.json();
  };
  const toPlace = (res) => ({
    name: res.address_components?.[0]?.long_name || res.formatted_address,
    subtitle: res.formatted_address,
    lat: res.geometry.location.lat,
    lng: res.geometry.location.lng,
    category: (res.types || [])[0] || 'Place',
    source: 'google',
  });
  return {
    name: 'google',
    async geocode(query) {
      const j = await get(`https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(query)}&key=${key}`);
      return (j.results || []).map(toPlace);
    },
    async reverseGeocode(p) {
      const j = await get(`https://maps.googleapis.com/maps/api/geocode/json?latlng=${p.lat},${p.lng}&key=${key}`);
      if (!j.results?.length) throw new ApiError(404, 'NO_ADDRESS', 'No address found');
      return { ...toPlace(j.results[0]), lat: p.lat, lng: p.lng };
    },
    async route(o, d) {
      const r = await fetch('https://routes.googleapis.com/directions/v2:computeRoutes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Goog-Api-Key': key, 'X-Goog-FieldMask': 'routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline' },
        body: JSON.stringify({
          origin: { location: { latLng: { latitude: o.lat, longitude: o.lng } } },
          destination: { location: { latLng: { latitude: d.lat, longitude: d.lng } } },
          travelMode: 'DRIVE',
        }),
      });
      if (!r.ok) throw new ApiError(502, 'MAPS_UPSTREAM_ERROR', `Routing provider HTTP ${r.status}`);
      const route = (await r.json()).routes?.[0];
      if (!route) throw new ApiError(422, 'NO_ROUTE', 'No route between these points');
      return {
        distanceMeters: route.distanceMeters,
        durationMinutes: Math.max(1, Math.ceil(parseInt(route.duration, 10) / 60)),
        polyline: decodePolyline(route.polyline.encodedPolyline),
        source: 'google',
      };
    },
  };
}

function adapterFor(cfg) {
  if (cfg.mapsProvider === 'dev') return devAdapter;
  if (cfg.mapsProvider === 'google' && cfg.mapsServerKey) return googleAdapter(cfg.mapsServerKey);
  return null;
}

function requireAdapter(cfg) {
  const a = adapterFor(cfg);
  if (!a) throw new ApiError(503, 'MAPS_PROVIDER_NOT_CONFIGURED', 'Maps provider is not configured');
  return a;
}

async function geocode(cfg, body) {
  const q = typeof body.query === 'string' ? body.query.trim().slice(0, 120) : '';
  return { results: await requireAdapter(cfg).geocode(q) };
}

async function reverseGeocode(cfg, body) {
  return requireAdapter(cfg).reverseGeocode(requireLatLng(body, 'point'));
}

/** Validates a trip and returns the route. Pickup and drop-off must be distinct. */
async function route(cfg, body) {
  const origin = requireLatLng(body.origin, 'origin');
  const destination = requireLatLng(body.destination, 'destination');
  if (haversineMeters(origin, destination) < MIN_TRIP_METERS) {
    throw new ApiError(422, 'PICKUP_EQUALS_DROPOFF', 'Pickup and drop-off must be at least 200 m apart');
  }
  return requireAdapter(cfg).route(origin, destination);
}

function validatePlace(p, name) {
  const { lat, lng } = requireLatLng(p, name);
  return { name: requireString(p.name, `${name}.name`, { max: 200 }), subtitle: typeof p.subtitle === 'string' ? p.subtitle.slice(0, 300) : '', lat, lng };
}

module.exports = { geocode, reverseGeocode, route, validatePlace, DEV_PLACES, decodePolyline, adapterFor };
