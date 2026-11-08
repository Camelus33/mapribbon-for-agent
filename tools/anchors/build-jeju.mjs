// Builds the two Jeju demo routes, both from Jeju City (Nohyeong) to Jungmun, and their road-facility anchors from
// OpenStreetMap:
//   J1100S  the 1100 Road (local route 1139) over the shoulder of Hallasan: hairpins below Eoseungsaeng, the 1100 m
//           summit, the 40 km/h descent past Yeongsil. The mountain road of the pair.
//   J1135S  Pyeonghwa-ro (1136 → 1135) to Donggwang, then 1116 and the 1132 ring road into Jungmun. The fast,
//           nearly straight road of the pair: same ends, so the curve classes can be compared side by side.
// Neither road has a tunnel or a toll gate; the facilities are junctions (where another numbered road leaves or the
// trip turns onto another road) and speed cameras. OSM maps no speed camera on either road (checked 2026-11-06), so
// the demo places labelled virtual ones, as on the Gangwon routes.
// Output (derived from OSM, so ODbL): app/src/main/assets/routes/<id>/{route.geojson,anchors.json}
// Usage: node tools/anchors/build-jeju.mjs   (Overpass answers are cached in tools/anchors/.cache)
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { overpass } from './lib/overpass.mjs';
import { haversine, cumulative, nearestOnPath } from './lib/geo.mjs';
import { english } from './lib/english.mjs';
import { segments, positionOf } from './lib/segments.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ASSETS = join(HERE, '../../app/src/main/assets/routes');
const CACHE = join(HERE, '.cache');

const BBOX = '33.22,126.25,33.56,126.75';
// Both trips leave from Nohyeong in Jeju City and end where the 1100 Road meets the 1132 ring road in Jungmun. Each
// starts on its own first road beside Nohyeong Crossroad (노형오거리), so neither begins with a turn it never makes.
const END = [126.4315, 33.2514];
const ROUTES = [
  {
    id: 'J1100S', dir: 'S', refs: ['1139'], start: [126.4800, 33.4845],
    name: '1100도로 제주시 → 중문', nameEn: '1100 Road over Hallasan: Jeju City → Jungmun',
    // One camera inside the 40 km/h stretch of the descent below Yeongsil (OSM maxspeed=40 from 26.1 km to 28.9 km),
    // where the island lowered the limit and installed enforcement in 2017.
    virtualCamerasAt: [0.78],
  },
  {
    id: 'J1135S', dir: 'S', refs: ['1136', '1135', '1116', '1132'], start: [126.4739, 33.4848], // 500 m down Nohyeong-ro, past the crossroad's turn
    name: '평화로 제주시 → 중문', nameEn: 'Pyeonghwa-ro via Donggwang: Jeju City → Jungmun',
    virtualCamerasAt: [0.33, 0.62],
  },
];
// Influence zones for two-lane roads: the same short approaches as on the Hangyeryeong route.
const ZONES = { JUNCTION: [400, 150], CAMERA: [300, 100] };
const MAIN = '^(trunk|primary|secondary|tertiary|trunk_link|primary_link|secondary_link|tertiary_link)$';
const refRe = ref => new RegExp(`(^|;)${ref}(;|$)`);
const hasRef = (w, refs) => refs.some(r => refRe(r).test(w.tags.ref ?? ''));

async function main() {
  const opts = { cacheDir: CACHE };
  const all = (await overpass(`[out:json][timeout:240];way["highway"~"${MAIN}"](${BBOX});out geom;`, opts)).elements;
  const named = (await overpass(`[out:json][timeout:180];way["highway"~"${MAIN}"]["ref"](${BBOX});node(w)["name"];out;`, opts)).elements;
  const cameras = (await overpass(`[out:json][timeout:120];node["highway"="speed_camera"](${BBOX});out;`, opts)).elements;
  const namedNode = new Map(named.map(n => [n.id, n]));
  const waysAt = new Map();
  for (const w of all) for (const n of w.nodes) (waysAt.get(n) ?? waysAt.set(n, []).get(n)).push(w);

  for (const ROUTE of ROUTES) {
    const { nodes, ways, path } = shortestPath(all, ROUTE.refs, ROUTE.start, END);
    const cum = cumulative(path);
    const lengthM = cum.at(-1);
    const anchors = [];
    const push = (type, name, alongM, source, sourceRef, extra = {}) => {
      const [before, after] = ZONES[type];
      anchors.push({ type, name, alongM: Math.round(alongM), endAlongM: Math.round(alongM), zoneBeforeM: before, zoneAfterM: after, source, sourceRef, virtual: false, ...extra });
    };

    // Junction candidates: where another numbered road touches the trip, and where the trip turns onto another road.
    const events = [];
    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i];
      const others = (waysAt.get(n) ?? []).filter(w => w.tags.ref && !/_link$/.test(w.tags.highway) && !hasRef(w, ROUTE.refs));
      if (others.length) events.push({ along: cum[i], node: n, ref: others[0].tags.ref.split(';')[0] });
    }
    // Named intersections on the trip itself (어승생삼거리, 어리목입구교차로, the Pyeonghwa-ro exits): where a driver
    // chooses, even when the side road carries no number.
    for (let i = 0; i < nodes.length; i++) {
      const pn = namedNode.get(nodes[i]);
      if (pn && /(교차로|삼거리|사거리|오거리|IC|입구)$/.test(pn.tags.name) && !pn.tags.highway?.startsWith('bus')) events.push({ along: cum[i], node: nodes[i], place: pn });
    }
    for (let i = 1; i < ways.length; i++) {
      const a = ways[i - 1].tags.ref, b = ways[i].tags.ref;
      if (!a || !b || a === b || ways[i].tags.junction === 'roundabout' || ways[i - 1].tags.junction === 'roundabout') continue;
      if (/_link$/.test(ways[i].tags.highway) || /_link$/.test(ways[i - 1].tags.highway)) continue;
      events.push({ along: cum[i], node: nodes[i], onto: ways[i] });
    }
    events.sort((a, b) => a.along - b.along);
    for (const e of events) {
      if (e.along < 300 || e.along > lengthM - 300) continue; // the trip's own ends are not junctions on it
      if (anchors.some(a => a.type === 'JUNCTION' && Math.abs(a.alongM - e.along) < 800)) continue;
      // A named intersection node within 250 m along the trip names the junction and places it (어승생삼거리 …).
      const place = nodes.map((n, i) => ({ n, i })).filter(({ n, i }) => namedNode.has(n) && Math.abs(cum[i] - e.along) < 250)
        .sort((x, y) => Math.abs(cum[x.i] - e.along) - Math.abs(cum[y.i] - e.along))[0];
      const pn = place ? namedNode.get(place.n) : null;
      if (pn) push('JUNCTION', pn.tags.name, cum[place.i], 'osm', `node/${pn.id}`, { nameEn: pn.tags['name:en'] ?? english(pn.tags.name) });
      else if (e.onto) {
        const road = e.onto.tags.name ?? `${e.onto.tags.ref}번 도로`;
        push('JUNCTION', `${road} 진입`, e.along, 'osm', `node/${e.node}`, { nameEn: `Onto ${e.onto.tags['name:en'] ?? english(road)}` });
      } else push('JUNCTION', `${e.ref}번 도로 갈림길`, e.along, 'osm', `node/${e.node}`, { nameEn: `Route ${e.ref} junction` });
    }

    // Mapped speed cameras within 40 m of the road, else virtual ones.
    for (const c of cameras) {
      const near = nearestOnPath(path, cum, [c.lon, c.lat]);
      if (near.dist > 40) continue;
      push('CAMERA', c.tags?.name ?? '단속카메라', near.along, 'osm', `node/${c.id}`, { nameEn: 'Speed camera' });
    }
    if (!anchors.some(a => a.type === 'CAMERA')) {
      for (const f of ROUTE.virtualCamerasAt) {
        let at = lengthM * f;
        while (anchors.some(a => Math.abs(a.alongM - at) < 700)) at += 250;
        push('CAMERA', '가상 단속카메라', at, 'virtual', null, { virtual: true, nameEn: 'Virtual speed camera' });
      }
    }

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
    const dir = join(ASSETS, ROUTE.id);
    await mkdir(dir, { recursive: true });
    await writeFile(join(dir, 'route.geojson'), JSON.stringify({
      type: 'Feature',
      properties: { id: ROUTE.id, name: ROUTE.name, dir: ROUTE.dir, lengthM: Math.round(lengthM), attribution },
      geometry: { type: 'LineString', coordinates: path.map(([x, y]) => [+x.toFixed(6), +y.toFixed(6)]) },
    }) + '\n');
    await writeFile(join(dir, 'anchors.json'), JSON.stringify({
      spec: 'maptalk.anchors/1', routeId: ROUTE.id, routeName: ROUTE.name, routeNameEn: ROUTE.nameEn, lengthM: Math.round(lengthM), attribution,
      anchors: anchors.map(({ id, type, name, dir = ROUTE.dir, alongM, endAlongM, zoneBeforeM, zoneAfterM, lat, lon, source, sourceRef, virtual, lengthM: l, nameEn }) =>
        ({ id, type, name, dir, alongM, endAlongM, zoneBeforeM, zoneAfterM, lat, lon, source, sourceRef, virtual, ...(l ? { lengthM: l } : {}), nameEn })),
    }, null, 2) + '\n');

    console.log(`route ${ROUTE.id}: ${(lengthM / 1000).toFixed(1)} km, ${path.length} points, cameras mapped nearby: ${cameras.length}`);
    for (const a of anchors) console.log(`${a.id.padEnd(10)} ${a.type.padEnd(8)} ${(a.alongM / 1000).toFixed(2).padStart(6)} km${a.endAlongM !== a.alongM ? `–${(a.endAlongM / 1000).toFixed(2)}` : ''}  ${a.name} / ${a.nameEn}${a.virtual ? ' (virtual)' : ''}`);
  }
}

/**
 * The trip as a shortest path over the directed road graph, weighted so that it stays on the route's own numbered
 * roads and only crosses an unnumbered roundabout or link where those roads meet (Pyeonghwa-ro and the 1116 road
 * do not share a node at Donggwang; the roundabout between them has no ref).
 */
function shortestPath(all, refs, start, end) {
  const pos = new Map(), adj = new Map();
  const add = (a, b, d, w) => (adj.get(a) ?? adj.set(a, []).get(a)).push([b, d, w]);
  for (const w of all) {
    const penalty = hasRef(w, refs) ? 1 : w.tags.ref ? 6 : (w.tags.junction === 'roundabout' || /_link$/.test(w.tags.highway) ? 1.5 : 4);
    w.nodes.forEach((n, i) => pos.set(n, [w.geometry[i].lon, w.geometry[i].lat]));
    const ow = w.tags.oneway === 'yes' || w.tags.junction === 'roundabout' ? 1 : w.tags.oneway === '-1' ? -1 : 0;
    for (let i = 1; i < w.nodes.length; i++) {
      const a = w.nodes[i - 1], b = w.nodes[i], d = haversine(pos.get(a), pos.get(b)) * penalty;
      if (ow >= 0) add(a, b, d, w);
      if (ow <= 0) add(b, a, d, w);
    }
  }
  const near = (p, r) => [...pos.keys()].filter(n => haversine(pos.get(n), p) < r).sort((a, b) => haversine(pos.get(a), p) - haversine(pos.get(b), p));
  const ends = near(end, 300);
  for (const s of near(start, 300)) {
    const dist = new Map([[s, 0]]), back = new Map(), done = new Set(), heap = new Heap();
    heap.push([0, s]);
    while (heap.size) {
      const [d, u] = heap.pop();
      if (done.has(u)) continue;
      done.add(u);
      for (const [v, w, way] of adj.get(u) ?? []) if (d + w < (dist.get(v) ?? Infinity)) { dist.set(v, d + w); back.set(v, [u, way]); heap.push([d + w, v]); }
    }
    const t = ends.find(n => dist.has(n));
    if (t === undefined) continue;
    const nodes = [t], ways = [];
    while (nodes[0] !== s) { const [p, way] = back.get(nodes[0]); nodes.unshift(p); ways.unshift(way); }
    return { nodes, ways, path: nodes.map(n => pos.get(n)) };
  }
  throw new Error(`no path for refs ${refs.join(',')} between the chosen ends`);
}

class Heap {
  constructor() { this.a = []; }
  get size() { return this.a.length; }
  push(x) { const a = this.a; a.push(x); let i = a.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (a[p][0] <= a[i][0]) break; [a[p], a[i]] = [a[i], a[p]]; i = p; } }
  pop() {
    const a = this.a, top = a[0], last = a.pop();
    if (a.length) { a[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < a.length && a[l][0] < a[m][0]) m = l; if (r < a.length && a[r][0] < a[m][0]) m = r; if (m === i) break; [a[m], a[i]] = [a[i], a[m]]; i = m; } }
    return top;
  }
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
