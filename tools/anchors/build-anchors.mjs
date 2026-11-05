// Builds the demo route and its road-facility anchors from OpenStreetMap.
// Output (both derived from OSM, so both are ODbL): app/src/main/assets/routes/SY60E/{route.geojson,anchors.json}
// Usage: node tools/anchors/build-anchors.mjs   (Overpass answers are cached in tools/anchors/.cache)
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { overpass } from './lib/overpass.mjs';
import { haversine, cumulative, nearestOnPath } from './lib/geo.mjs';
import { english } from './lib/english.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ASSETS = join(HERE, '../../app/src/main/assets/routes/SY60E');
const CACHE = join(HERE, '.cache');

// Seoul–Yangyang Expressway (ref 60), eastbound from Inje IC to Yangyang JC.
const ROUTE = { id: 'SY60E', name: '서울양양고속도로 인제IC → 양양JC', nameEn: 'Seoul-Yangyang Expressway: Inje IC → Yangyang JC', dir: 'E', ref: '60' };
const BBOX = '37.88,128.25,38.10,128.65';
const START = [128.2894, 37.9146]; // 인제IC
const END = [128.5967, 38.0633];   // 양양JC (ref 60 ends here eastbound)

// Influence zone around each facility, in meters before and after it along the route.
const ZONES = { TUNNEL: [300, 300], JUNCTION: [1000, 200], TOLL: [1000, 100], CAMERA: [400, 100] };
// No speed camera on this route is mapped in OSM (checked 2026-11-05), so the demo places labelled virtual ones.
const VIRTUAL_CAMERAS_AT = [0.22, 0.58];

const ll = e => [e.lon ?? e.center?.lon, e.lat ?? e.center?.lat];

async function main() {
  const opts = { cacheDir: CACHE };
  const ways = (await overpass(`[out:json][timeout:120];way["highway"="motorway"]["ref"="${ROUTE.ref}"](${BBOX});out geom;`, opts)).elements;
  const junctions = (await overpass(`[out:json][timeout:120];way["highway"="motorway"]["ref"="${ROUTE.ref}"](${BBOX});node(w)["highway"="motorway_junction"];out;`, opts)).elements;
  const tolls = (await overpass(`[out:json][timeout:120];(nwr["barrier"="toll_booth"](${BBOX});nwr["highway"="toll_gantry"](${BBOX}););out center;`, opts)).elements;

  const chain = buildChain(ways);
  const nodes = [], path = [];
  for (const w of chain) {
    w.geometry.forEach((g, i) => {
      if (path.length && i === 0) return; // shared node between consecutive ways
      path.push([g.lon, g.lat]); nodes.push(w.nodes[i]);
    });
  }
  // Trim to Inje IC .. Yangyang JC.
  const cumAll = cumulative(path);
  const s = nearestOnPath(path, cumAll, START), e = nearestOnPath(path, cumAll, END);
  if (s.dist > 300 || e.dist > 300 || e.along <= s.along) throw new Error(`route ends not found (start ${s.dist}m, end ${e.dist}m)`);
  const from = s.index, to = e.index + 1;
  const routePath = path.slice(from, to + 1), routeNodes = nodes.slice(from, to + 1);
  const cum = cumulative(routePath);
  const lengthM = cum[cum.length - 1];

  const anchors = [];
  const push = (type, name, alongM, endAlongM, source, sourceRef, extra = {}) => {
    const [before, after] = ZONES[type];
    anchors.push({ type, name, alongM: Math.round(alongM), endAlongM: Math.round(endAlongM ?? alongM), zoneBeforeM: before, zoneAfterM: after, source, sourceRef, virtual: false, ...extra });
  };

  // Tunnels: consecutive tunnel ways with the same tunnel name become one anchor. "…교" names are mis-tagged bridges.
  let offset = 0, open = null;
  const chainSpans = [];
  for (const w of chain) {
    const len = cumulative(w.geometry.map(g => [g.lon, g.lat])).at(-1);
    chainSpans.push({ w, from: offset - cumAll[from], to: offset + len - cumAll[from] });
    offset += len;
  }
  for (const { w, from: a, to: b } of chainSpans) {
    const name = w.tags['tunnel:name'] || w.tags['tunnel:name:ko'];
    const isTunnel = w.tags.tunnel === 'yes' && name && !name.endsWith('교');
    if (isTunnel && open && open.name === name && Math.abs(open.to - a) < 5) { open.to = b; open.refs.push(`way/${w.id}`); continue; }
    if (open) { flushTunnel(open); open = null; }
    if (isTunnel) open = { name, nameEn: w.tags['tunnel:name:en'], from: a, to: b, refs: [`way/${w.id}`] };
  }
  if (open) flushTunnel(open);
  function flushTunnel(t) {
    if (t.to < 0 || t.from > lengthM) return;
    push('TUNNEL', t.name, Math.max(0, t.from), Math.min(lengthM, t.to), 'osm', t.refs.join(','), { lengthM: Math.round(t.to - t.from), nameEn: t.nameEn ?? english(t.name) });
  }

  // Junctions that sit on this carriageway. Rest areas are sometimes tagged as junctions; drop them.
  // An IC can carry two junction nodes a few hundred meters apart (exit and entry); the first one is the decision point.
  const onRoute = junctions
    .map(j => ({ j, idx: routeNodes.indexOf(j.id) }))
    .filter(({ j, idx }) => idx >= 0 && !/휴게소|쉼터/.test(j.tags?.name ?? ''))
    .sort((a, b) => cum[a.idx] - cum[b.idx]);
  for (const { j, idx } of onRoute) {
    if (anchors.some(a => a.type === 'JUNCTION' && a.name === j.tags.name && Math.abs(a.alongM - cum[idx]) < 1000)) continue;
    push('JUNCTION', j.tags.name, cum[idx], null, 'osm', `node/${j.id}`, { nameEn: j.tags['name:en'] ?? english(j.tags.name) });
  }

  // Toll gates on the main line only (ramp toll gates are not passed by through traffic).
  const tollSeen = [];
  for (const t of tolls) {
    const name = t.tags?.name; if (!name) continue;
    const near = nearestOnPath(routePath, cum, ll(t));
    if (near.dist > 80 || tollSeen.some(a => Math.abs(a - near.along) < 300)) continue;
    tollSeen.push(near.along);
    push('TOLL', name, near.along, null, 'osm', `${t.type}/${t.id}`, { nameEn: t.tags['name:en'] ?? english(name) });
  }

  // Virtual cameras, kept out of tunnels so the demo signal stays readable.
  for (const f of VIRTUAL_CAMERAS_AT) {
    let at = lengthM * f;
    while (anchors.some(a => a.type === 'TUNNEL' && at >= a.alongM - 400 && at <= a.endAlongM + 400)) at += 250;
    push('CAMERA', '가상 단속카메라', at, null, 'virtual', null, { virtual: true, nameEn: 'Virtual speed camera' });
  }

  anchors.sort((a, b) => a.alongM - b.alongM);
  const counters = {};
  for (const a of anchors) {
    const k = a.type[0];
    counters[k] = (counters[k] ?? 0) + 1;
    a.id = `${ROUTE.id}-${k}${String(counters[k]).padStart(2, '0')}`;
    const p = pointAt(routePath, cum, a.alongM);
    a.lon = +p[0].toFixed(6); a.lat = +p[1].toFixed(6);
  }

  const attribution = '© OpenStreetMap contributors (ODbL 1.0, https://www.openstreetmap.org/copyright)';
  await mkdir(ASSETS, { recursive: true });
  await writeFile(join(ASSETS, 'route.geojson'), JSON.stringify({
    type: 'Feature',
    properties: { id: ROUTE.id, name: ROUTE.name, dir: ROUTE.dir, lengthM: Math.round(lengthM), attribution },
    geometry: { type: 'LineString', coordinates: routePath.map(([x, y]) => [+x.toFixed(6), +y.toFixed(6)]) },
  }) + '\n');
  await writeFile(join(ASSETS, 'anchors.json'), JSON.stringify({
    spec: 'maptalk.anchors/1', routeId: ROUTE.id, routeName: ROUTE.name, routeNameEn: ROUTE.nameEn, lengthM: Math.round(lengthM), attribution,
    anchors: anchors.map(({ id, type, name, dir = ROUTE.dir, alongM, endAlongM, zoneBeforeM, zoneAfterM, lat, lon, source, sourceRef, virtual, lengthM: l, nameEn }) =>
      ({ id, type, name, dir, alongM, endAlongM, zoneBeforeM, zoneAfterM, lat, lon, source, sourceRef, virtual, ...(l ? { lengthM: l } : {}), nameEn })),
  }, null, 2) + '\n');

  console.log(`route ${ROUTE.id}: ${(lengthM / 1000).toFixed(1)} km, ${routePath.length} points`);
  for (const a of anchors) console.log(`${a.id.padEnd(10)} ${a.type.padEnd(8)} ${(a.alongM / 1000).toFixed(2).padStart(6)} km${a.endAlongM !== a.alongM ? `–${(a.endAlongM / 1000).toFixed(2)}` : ''}  ${a.name}${a.virtual ? ' (virtual)' : ''}`);
}

/** Follows one-way ways by shared end/start nodes from the way nearest START until it passes END. */
function buildChain(ways) {
  const byStart = new Map();
  for (const w of ways) (byStart.get(w.nodes[0]) ?? byStart.set(w.nodes[0], []).get(w.nodes[0])).push(w);
  const dist = (w, p) => Math.min(...w.geometry.map(g => haversine([g.lon, g.lat], p)));
  const candidates = ways.filter(w => dist(w, START) < 400);
  for (const first of candidates) {
    const chain = [first], seen = new Set([first.id]);
    while (chain.length < 1000) {
      const last = chain.at(-1);
      if (dist(last, END) < 400) return chain;
      const next = (byStart.get(last.nodes.at(-1)) ?? []).find(w => !seen.has(w.id));
      if (!next) break;
      chain.push(next); seen.add(next.id);
    }
  }
  throw new Error('no eastbound chain from Inje IC to Yangyang JC');
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
