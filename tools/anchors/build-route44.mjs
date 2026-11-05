// Builds the second demo route, National Route 44 over Hangyeryeong Pass (Inje → Yangyang, eastbound), and its
// road-facility anchors from OpenStreetMap. The same trip as the expressway route, on a mountain road: the pair shows
// what the curve classes mean.
// Output (derived from OSM, so ODbL): app/src/main/assets/routes/N44E/{route.geojson,anchors.json}
// Usage: node tools/anchors/build-route44.mjs   (Overpass answers are cached in tools/anchors/.cache)
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { overpass } from './lib/overpass.mjs';
import { haversine, cumulative, nearestOnPath } from './lib/geo.mjs';
import { english } from './lib/english.mjs';
import { segments, positionOf } from './lib/segments.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ASSETS = join(HERE, '../../app/src/main/assets/routes/N44E');
const CACHE = join(HERE, '.cache');

const ROUTE = {
  id: 'N44E', dir: 'E', ref: '44',
  name: '국도44호선(한계령) 인제 → 양양',
  nameEn: 'National Route 44 (Hangyeryeong Pass): Inje → Yangyang',
};
const BBOX = '37.85,128.10,38.20,128.70';
const START = [128.1756, 38.0654]; // 인제읍, where route 44 runs through the town
const END = [128.5967, 38.0633];   // 양양, next to the expressway's Yangyang JC so both routes end at the same place
const REF = /(^|;)44(;|$)/;

// Influence zones for a two-lane mountain road: shorter approaches than on the expressway.
const ZONES = { TUNNEL: [300, 300], JUNCTION: [400, 150], TOLL: [500, 100], CAMERA: [300, 100] };
// Virtual cameras where no mapped one exists, as on the expressway route (labelled "virtual" in the app).
const VIRTUAL_CAMERAS_AT = [0.74];

async function main() {
  const opts = { cacheDir: CACHE };
  const ways = (await overpass(`[out:json][timeout:180];way["highway"~"^(trunk|primary|secondary)$"]["ref"~"(^|;)44(;|$)"](${BBOX});out geom;`, opts)).elements;
  // Every numbered road that touches route 44: their shared nodes are the junctions.
  const crossing = (await overpass(`[out:json][timeout:180];way["highway"~"^(trunk|primary|secondary)$"]["ref"~"(^|;)44(;|$)"](${BBOX});node(w)->.r;way(bn.r)["highway"~"^(trunk|primary|secondary|tertiary)$"]["ref"];out body;`, opts)).elements;
  const cameras = (await overpass(`[out:json][timeout:120];node["highway"="speed_camera"](${BBOX});out;`, opts)).elements;

  const { path, nodes } = shortestEastbound(ways);
  const cum = cumulative(path);
  const lengthM = cum.at(-1);

  const anchors = [];
  const push = (type, name, alongM, endAlongM, source, sourceRef, extra = {}) => {
    const [before, after] = ZONES[type];
    anchors.push({ type, name, alongM: Math.round(alongM), endAlongM: Math.round(endAlongM ?? alongM), zoneBeforeM: before, zoneAfterM: after, source, sourceRef, virtual: false, ...extra });
  };

  // Tunnels: route segments that lie on a way tagged tunnel=yes, grouped by tunnel name.
  const wayOf = new Map();
  for (const w of ways) for (let i = 1; i < w.nodes.length; i++) wayOf.set(`${w.nodes[i - 1]}>${w.nodes[i]}`, w).set(`${w.nodes[i]}>${w.nodes[i - 1]}`, w);
  let open = null;
  for (let i = 1; i < nodes.length; i++) {
    const w = wayOf.get(`${nodes[i - 1]}>${nodes[i]}`);
    const name = w?.tags.tunnel === 'yes' ? (w.tags['tunnel:name'] || w.tags.name) : null;
    const isTunnel = name && name.endsWith('터널');
    if (isTunnel && open && open.name === name) { open.to = cum[i]; if (!open.refs.includes(`way/${w.id}`)) open.refs.push(`way/${w.id}`); continue; }
    if (open) { flushTunnel(open); open = null; }
    if (isTunnel) open = { name, nameEn: w.tags['tunnel:name:en'] || w.tags['name:en'], from: cum[i - 1], to: cum[i], refs: [`way/${w.id}`] };
  }
  if (open) flushTunnel(open);
  function flushTunnel(t) {
    push('TUNNEL', t.name, t.from, t.to, 'osm', t.refs.join(','), { lengthM: Math.round(t.to - t.from), nameEn: t.nameEn ?? english(t.name) });
  }

  // Junctions: where another numbered road leaves route 44. One anchor per road and place.
  const routeNodeIndex = new Map(nodes.map((n, i) => [n, i]));
  const found = [];
  for (const w of crossing) {
    if (REF.test(w.tags.ref)) continue; // a carriageway of route 44 itself
    const other = w.tags.ref.split(";");
    for (const n of w.nodes ?? []) {
      const i = routeNodeIndex.get(n);
      if (i === undefined) continue;
      found.push({ along: cum[i], ref: other[0], node: n });
    }
  }
  found.sort((a, b) => a.along - b.along);
  for (const f of found) {
    if (f.along < 200 || f.along > lengthM - 200) continue; // the trip's own start and end are not junctions on it
    if (anchors.some(a => a.type === 'JUNCTION' && Math.abs(a.alongM - f.along) < 800)) continue;
    push('JUNCTION', `${f.ref}번 도로 갈림길`, f.along, null, 'osm', `node/${f.node}`, { nameEn: `Route ${f.ref} junction` });
  }

  // Mapped speed cameras within 40 m of the road, else virtual ones.
  for (const c of cameras) {
    const near = nearestOnPath(path, cum, [c.lon, c.lat]);
    if (near.dist > 40) continue;
    push('CAMERA', c.tags?.name ?? '단속카메라', near.along, null, 'osm', `node/${c.id}`, { nameEn: 'Speed camera' });
  }
  if (!anchors.some(a => a.type === 'CAMERA')) {
    for (const f of VIRTUAL_CAMERAS_AT) {
      let at = lengthM * f;
      while (anchors.some(a => Math.abs(a.alongM - at) < 500 || (a.type === 'TUNNEL' && at >= a.alongM - 400 && at <= a.endAlongM + 400))) at += 250;
      push('CAMERA', '가상 단속카메라', at, null, 'virtual', null, { virtual: true, nameEn: 'Virtual speed camera' });
    }
  }

  // Segments of open road between the facilities, so events between two facilities have a place too.
  anchors.push(...segments(anchors, lengthM).map(s => ({ ...s, dir: ROUTE.dir })));
  anchors.sort((a, b) => a.alongM - b.alongM);
  const counters = {};
  for (const a of anchors) {
    const k = a.type[0];
    counters[k] = (counters[k] ?? 0) + 1;
    a.id = `${ROUTE.id}-${k}${String(counters[k]).padStart(2, '0')}`;
    const p = pointAt(path, cum, positionOf(a));
    a.lon = +p[0].toFixed(6); a.lat = +p[1].toFixed(6);
  }

  const attribution = '© OpenStreetMap contributors (ODbL 1.0, https://www.openstreetmap.org/copyright)';
  await mkdir(ASSETS, { recursive: true });
  await writeFile(join(ASSETS, 'route.geojson'), JSON.stringify({
    type: 'Feature',
    properties: { id: ROUTE.id, name: ROUTE.name, dir: ROUTE.dir, lengthM: Math.round(lengthM), attribution },
    geometry: { type: 'LineString', coordinates: path.map(([x, y]) => [+x.toFixed(6), +y.toFixed(6)]) },
  }) + '\n');
  await writeFile(join(ASSETS, 'anchors.json'), JSON.stringify({
    spec: 'maptalk.anchors/1', routeId: ROUTE.id, routeName: ROUTE.name, routeNameEn: ROUTE.nameEn, lengthM: Math.round(lengthM), attribution,
    anchors: anchors.map(({ id, type, name, dir = ROUTE.dir, alongM, endAlongM, zoneBeforeM, zoneAfterM, lat, lon, source, sourceRef, virtual, lengthM: l, nameEn }) =>
      ({ id, type, name, dir, alongM, endAlongM, zoneBeforeM, zoneAfterM, lat, lon, source, sourceRef, virtual, ...(l ? { lengthM: l } : {}), nameEn })),
  }, null, 2) + '\n');

  console.log(`route ${ROUTE.id}: ${(lengthM / 1000).toFixed(1)} km, ${path.length} points, cameras mapped nearby: ${cameras.length}`);
  for (const a of anchors) console.log(`${a.id.padEnd(10)} ${a.type.padEnd(8)} ${(a.alongM / 1000).toFixed(2).padStart(6)} km${a.endAlongM !== a.alongM ? `–${(a.endAlongM / 1000).toFixed(2)}` : ''}  ${a.name} / ${a.nameEn}${a.virtual ? ' (virtual)' : ''}`);
}

/**
 * Route 44 is a two-way road with one-way pairs through towns, so the eastbound trip is a shortest path over the
 * directed road graph from the node nearest START to the node nearest END.
 */
function shortestEastbound(ways) {
  const pos = new Map(), adj = new Map();
  const add = (a, b, d) => (adj.get(a) ?? adj.set(a, []).get(a)).push([b, d]);
  for (const w of ways) {
    w.nodes.forEach((n, i) => pos.set(n, [w.geometry[i].lon, w.geometry[i].lat]));
    const ow = w.tags.oneway === 'yes' ? 1 : w.tags.oneway === '-1' ? -1 : 0;
    for (let i = 1; i < w.nodes.length; i++) {
      const a = w.nodes[i - 1], b = w.nodes[i], d = haversine(pos.get(a), pos.get(b));
      if (ow >= 0) add(a, b, d);
      if (ow <= 0) add(b, a, d);
    }
  }
  // In towns the nearest node may sit on the westbound half of a one-way pair, so try the start candidates in order of
  // distance and take the first that reaches an end candidate; of those, the end nearest END.
  const near = (p, r) => [...pos.keys()].filter(n => haversine(pos.get(n), p) < r).sort((a, b) => haversine(pos.get(a), p) - haversine(pos.get(b), p));
  const ends = near(END, 1500);
  let s, t, prev;
  for (const cand of near(START, 400)) {
    const dist = new Map([[cand, 0]]), back = new Map(), done = new Set();
    const queue = [[0, cand]];
    while (queue.length) {
      queue.sort((a, b) => a[0] - b[0]);
      const [d, u] = queue.shift();
      if (done.has(u)) continue;
      done.add(u);
      for (const [v, w] of adj.get(u) ?? []) if (d + w < (dist.get(v) ?? Infinity)) { dist.set(v, d + w); back.set(v, u); queue.push([d + w, v]); }
    }
    const end = ends.find(n => dist.has(n));
    if (end !== undefined) { s = cand; t = end; prev = back; break; }
  }
  if (s === undefined) throw new Error("no eastbound path on route 44 between the chosen ends");
  const nodes = [t];
  while (nodes[0] !== s) nodes.unshift(prev.get(nodes[0]));
  return { nodes, path: nodes.map(n => pos.get(n)) };
}

function pointAt(path, cum, along) {
  for (let i = 1; i < path.length; i++) {
    if (cum[i] >= along) {
      const t = (along - cum[i - 1]) / Math.max(1e-9, cum[i] - cum[i - 1]);
      return [path[i - 1][0] + t * (path[i][0] - path[i - 1][0]), path[i - 1][1] + t * (path[i][1] - path[i - 1][1])];
    }
  }
  return path.at(-1);
}

main().catch(err => { console.error(err.message); process.exit(1); });
