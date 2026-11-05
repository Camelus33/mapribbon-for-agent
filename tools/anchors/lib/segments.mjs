// Segment anchors: the open road between tunnels cut into stretches of about `stepM`, so that what happens between
// two facilities has a place to hang a ribbon on. Tunnels are left out (no position inside them); a stretch of open
// road shorter than `minM` between two tunnels gets no segment. Equal lengths within each stretch of open road.

const km = m => (m / 1000).toFixed(1);

export function segments(anchors, lengthM, { stepM = 2000, minM = 500 } = {}) {
  const tunnels = anchors.filter(a => a.type === 'TUNNEL').sort((a, b) => a.alongM - b.alongM);
  const open = [];
  let from = 0;
  for (const t of tunnels) {
    if (t.alongM - from >= minM) open.push([from, t.alongM]);
    from = Math.max(from, t.endAlongM);
  }
  if (lengthM - from >= minM) open.push([from, lengthM]);

  const out = [];
  for (const [a, b] of open) {
    const n = Math.max(1, Math.round((b - a) / stepM));
    for (let i = 0; i < n; i++) {
      const f = Math.round(a + (b - a) * i / n), t = Math.round(a + (b - a) * (i + 1) / n);
      out.push({
        type: 'SEGMENT', name: `${km(f)}–${km(t)} km`, nameEn: `km ${km(f)}–${km(t)}`,
        alongM: f, endAlongM: t, zoneBeforeM: 0, zoneAfterM: 0,
        source: 'route', sourceRef: null, virtual: false, lengthM: t - f,
      });
    }
  }
  return out;
}

/** Where an anchor's lat/lon is taken: a segment's middle, any other anchor's start. */
export const positionOf = a => a.type === 'SEGMENT' ? (a.alongM + a.endAlongM) / 2 : a.alongM;
