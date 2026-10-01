'use strict';
// Authoritative fare engine. Integer paise throughout, deterministic half-up rounding.
//
//   transit   = round((distanceCharge + timeCharge) x category x luxury x engineCc)
//   subtotal  = base + transit
//   taxable   = subtotal + platformSafetyFee
//   gst       = round(taxable x 5%)
//   total     = taxable + gst                  <- exactly what is held and captured
//   pilot     = round(taxable x 85%), platform = taxable - pilot  (sums exactly)
//
// The algorithm is mirrored by the Android client (domain/FareCalculator.kt); both are checked
// against contracts/fare-test-vectors.json so the displayed and charged fare cannot diverge.
const { roundDiv, ApiError } = require('./util');

const BP = 10000; // multipliers are stored as integer basis points (x 10000)
const GST_PERCENT = 5;
const PILOT_PERCENT = 85;

// Rates in paise, multipliers in basis points. Same values as the in-app catalog.
const VEHICLES = {
  moto_commuter: { name: 'Moto Commuter', group: 'TWO_WHEELER', baseFare: 2000, perKm: 850, perMin: 80, categoryBp: 10000, luxuryBp: 10000, engineCcBp: 10000, safetyFee: 500 },
  moto_sports: { name: 'Moto Sports & Cruise', group: 'TWO_WHEELER', baseFare: 3500, perKm: 1200, perMin: 120, categoryBp: 12000, luxuryBp: 12000, engineCcBp: 13500, safetyFee: 800 },
  moto_electric: { name: 'Electric Green Scooter', group: 'TWO_WHEELER', baseFare: 2500, perKm: 900, perMin: 90, categoryBp: 10500, luxuryBp: 10000, engineCcBp: 10500, safetyFee: 600 },
  cab_mini: { name: 'PayLift Mini', group: 'FOUR_WHEELER', baseFare: 5000, perKm: 1400, perMin: 160, categoryBp: 12500, luxuryBp: 10000, engineCcBp: 10000, safetyFee: 1200 },
  cab_sedan: { name: 'Prime Sedan', group: 'FOUR_WHEELER', baseFare: 7000, perKm: 1750, perMin: 200, categoryBp: 14500, luxuryBp: 12000, engineCcBp: 11500, safetyFee: 1500 },
  cab_suv: { name: 'PayLift SUV Max', group: 'FOUR_WHEELER', baseFare: 11000, perKm: 2400, perMin: 280, categoryBp: 18000, luxuryBp: 14000, engineCcBp: 14000, safetyFee: 2000 },
  cab_luxury: { name: 'Executive Black', group: 'FOUR_WHEELER', baseFare: 28000, perKm: 4500, perMin: 500, categoryBp: 24000, luxuryBp: 20000, engineCcBp: 16000, safetyFee: 4500 },
};

const MIN_DISTANCE_M = 200;
const MAX_DISTANCE_M = 150000;
const MAX_DURATION_MIN = 600;

function calculate(vehicleId, distanceMeters, durationMinutes) {
  const v = VEHICLES[vehicleId];
  if (!v) throw new ApiError(400, 'UNKNOWN_VEHICLE', `Unknown vehicle ${vehicleId}`);
  if (!Number.isSafeInteger(distanceMeters) || distanceMeters < MIN_DISTANCE_M || distanceMeters > MAX_DISTANCE_M) {
    throw new ApiError(422, 'INVALID_DISTANCE', `Trip distance must be between ${MIN_DISTANCE_M} m and ${MAX_DISTANCE_M} m`);
  }
  if (!Number.isSafeInteger(durationMinutes) || durationMinutes < 1 || durationMinutes > MAX_DURATION_MIN) {
    throw new ApiError(422, 'INVALID_DURATION', 'Trip duration is out of range');
  }
  const distanceCharge = roundDiv(distanceMeters * v.perKm, 1000);
  const timeCharge = durationMinutes * v.perMin;
  const factorBp = roundDiv(roundDiv(v.categoryBp * v.luxuryBp, BP) * v.engineCcBp, BP);
  const transit = roundDiv((distanceCharge + timeCharge) * factorBp, BP);
  const subtotal = v.baseFare + transit;
  const taxable = subtotal + v.safetyFee;
  const gst = roundDiv(taxable * GST_PERCENT, 100);
  const total = taxable + gst;
  const pilotEarning = roundDiv(taxable * PILOT_PERCENT, 100);
  return {
    vehicleId,
    currency: 'INR',
    distanceMeters,
    durationMinutes,
    baseFarePaise: v.baseFare,
    perKmRatePaise: v.perKm,
    perMinRatePaise: v.perMin,
    distanceChargePaise: distanceCharge,
    durationChargePaise: timeCharge,
    categoryMultiplierBp: v.categoryBp,
    luxuryMultiplierBp: v.luxuryBp,
    engineCcFactorBp: v.engineCcBp,
    combinedFactorBp: factorBp,
    transitChargePaise: transit,
    subtotalPaise: subtotal,
    platformSafetyFeePaise: v.safetyFee,
    taxablePaise: taxable,
    gstPercent: GST_PERCENT,
    gstPaise: gst,
    totalPaise: total,
    pilotPercent: PILOT_PERCENT,
    pilotEarningPaise: pilotEarning,
    platformCommissionPaise: taxable - pilotEarning,
  };
}

module.exports = { calculate, VEHICLES, MIN_DISTANCE_M, MAX_DISTANCE_M };
