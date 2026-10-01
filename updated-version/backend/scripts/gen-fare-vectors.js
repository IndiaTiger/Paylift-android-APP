'use strict';
// Regenerates contracts/fare-test-vectors.json from the authoritative server fare engine.
const fs = require('node:fs');
const path = require('node:path');
const fare = require('../src/fare');
const cases = [[200, 1], [1500, 5], [5200, 16], [7777, 23], [12345, 37], [48000, 95], [150000, 600]];
const out = [];
for (const vehicleId of Object.keys(fare.VEHICLES)) {
  for (const [distanceMeters, durationMinutes] of cases) {
    out.push({ vehicleId, distanceMeters, durationMinutes, expected: fare.calculate(vehicleId, distanceMeters, durationMinutes) });
  }
}
fs.writeFileSync(path.join(__dirname, '..', '..', 'contracts', 'fare-test-vectors.json'), JSON.stringify(out, null, 2) + '\n');
console.log(`wrote ${out.length} vectors`);
