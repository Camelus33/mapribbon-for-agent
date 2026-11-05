// Zero-dependency geometry helpers. Copied from habitus33 scripts/atlas/lib/geo.mjs (subset).
// Coordinates are GeoJSON order: [lon, lat], distances in meters.
const R = 6371008.8;
const toRad = d => (d * Math.PI) / 180;

export function haversine([lon1, lat1], [lon2, lat2]) {
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

export function cumulative(path) {
  const out = new Float64Array(path.length);
  for (let i = 1; i < path.length; i++) out[i] = out[i - 1] + haversine(path[i - 1], path[i]);
  return out;
}

const planar = (p, lat0) => [toRad(p[0]) * Math.cos(toRad(lat0)) * R, toRad(p[1]) * R];

/** Distance from p to segment a-b (local planar approximation; fine for segments of a few km). */
export function pointSegment(p, a, b) {
  const lat0 = (a[1] + b[1]) / 2;
  const P = planar(p, lat0), A = planar(a, lat0), B = planar(b, lat0);
  const dx = B[0] - A[0], dy = B[1] - A[1], len2 = dx * dx + dy * dy;
  let t = len2 === 0 ? 0 : ((P[0] - A[0]) * dx + (P[1] - A[1]) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  return { dist: Math.hypot(P[0] - (A[0] + t * dx), P[1] - (A[1] + t * dy)), t };
}

/** Nearest point on a path. `cum` is cumulative(path); returns distance from the path and distance along it. */
export function nearestOnPath(path, cum, p) {
  if (path.length < 2) throw new Error('PATH_TOO_SHORT');
  let best = { dist: Infinity, along: 0, index: 0 };
  for (let i = 0; i < path.length - 1; i++) {
    const { dist, t } = pointSegment(p, path[i], path[i + 1]);
    if (dist < best.dist) best = { dist, along: cum[i] + t * (cum[i + 1] - cum[i]), index: i };
  }
  return best;
}
