// Adds the height of the road to a built route: app/src/main/assets/routes/<id>/elevation.json.
//
// Heights come from the Copernicus DEM GLO-90 (about 90 m cells), read through the Open-Meteo elevation API. A
// terrain model gives the height of the ground, not of the road: inside a tunnel it would put the car on top of the
// mountain, on a bridge at the bottom of the valley. So every stretch of the route that runs on an OSM bridge or in an
// OSM tunnel takes a straight line between the heights at its two ends instead.
//
// The profile is sampled every STEP_M metres along the route line and lightly smoothed (a 90 m model is noisy at
// 50 m steps). Heights are whole metres.
//
// Usage: node tools/anchors/build-elevation.mjs J1100S [more route ids]
// Answers from both services are cached in tools/anchors/.cache.
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { overpass } from './lib/overpass.mjs';
import { cumulative, nearestOnPath } from './lib/geo.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ASSETS = join(HERE, '../../app/src/main/assets/routes');
const CACHE = join(HERE, '.cache');

const STEP_M = 50;
const SMOOTH_M = 150; // half-width of the moving average
const ON_STRUCTURE_M = 12; // a route point this close to a bridge or tunnel way runs on it
const MIN_STRUCTURE_M = 60; // shorter crossings are culverts and small bridges a 90 m model cannot see anyway
const ELEVATION_API = 'https://api.open-meteo.com/v1/elevation';
const BATCH = 100; // the API takes at most 100 points per request
const SOURCE = 'Copernicus DEM GLO-90 (Open-Meteo elevation API)';
const ATTRIBUTION = '© DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH 2014–2018, provided under COPERNICUS by the European Union and ESA';

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function elevations(points) {
  const out = [];
  for (let i = 0; i < points.length; i += BATCH) {
    const chunk = points.slice(i, i + BATCH);
    const lat = chunk.map(p => p[1].toFixed(5)).join(',');
    const lon = chunk.map(p => p[0].toFixed(5)).join(',');
    const url = `${ELEVATION_API}?latitude=${lat}&longitude=${lon}`;
    const file = join(CACHE, `elev-${createHash('sha256').update(url).digest('hex').slice(0, 24)}.json`);
    let json;
    try { json = JSON.parse(await readFile(file, 'utf8')); } catch {
      for (let attempt = 0; ; attempt++) {
        const res = await fetch(url, { headers: { 'User-Agent': 'maptalk-drive-anchors/0.1 (build-time only)' } });
        if (res.ok) { json = await res.json(); break; }
        if (attempt === 4) throw new Error(`elevation API failed: http ${res.status}`);
        await sleep(3000 * (attempt + 1));
      }
      if (!Array.isArray(json.elevation) || json.elevation.length !== chunk.length) throw new Error('elevation API returned a short answer');
      await mkdir(CACHE, { recursive: true });
      await writeFile(file, JSON.stringify(json));
      await sleep(300);
    }
    out.push(...json.elevation);
  }
  return out;
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

/** Stretches of the route (in metres along it) that run on a bridge or through a tunnel mapped in OSM. */
async function structures(path, cum) {
  const lons = path.map(p => p[0]), lats = path.map(p => p[1]);
  const pad = 0.005;
  const bbox = [Math.min(...lats) - pad, Math.min(...lons) - pad, Math.max(...lats) + pad, Math.max(...lons) + pad].map(v => v.toFixed(4)).join(',');
  const ways = (await overpass(`[out:json][timeout:180];(way["highway"]["bridge"]["bridge"!="no"](${bbox});way["highway"]["tunnel"]["tunnel"!="no"](${bbox}););out geom;`, { cacheDir: CACHE })).elements;
  const stretches = [];
  for (const w of ways) {
    // Where the way's own nodes fall on the route: a way the route runs along has all its nodes next to it.
    const hits = w.geometry.map(g => nearestOnPath(path, cum, [g.lon, g.lat]));
    if (hits.some(h => h.dist > ON_STRUCTURE_M)) continue;
    const from = Math.min(...hits.map(h => h.along)), to = Math.max(...hits.map(h => h.along));
    if (to - from >= MIN_STRUCTURE_M) stretches.push({ from, to, kind: w.tags.tunnel && w.tags.tunnel !== 'no' ? 'tunnel' : 'bridge', name: w.tags.name ?? w.tags['tunnel:name'] ?? w.tags['bridge:name'] ?? '' });
  }
  // A twin carriageway or a way split in two gives overlapping pieces: merge them.
  stretches.sort((a, b) => a.from - b.from);
  const merged = [];
  for (const s of stretches) {
    const last = merged.at(-1);
    if (last && s.from <= last.to + STEP_M) { last.to = Math.max(last.to, s.to); if (s.kind === 'tunnel') last.kind = 'tunnel'; last.name ||= s.name; }
    else merged.push({ ...s });
  }
  return merged;
}

async function build(id) {
  const dir = join(ASSETS, id);
  const geo = JSON.parse(await readFile(join(dir, 'route.geojson'), 'utf8'));
  const path = geo.geometry.coordinates;
  const cum = cumulative(path);
  const length = cum.at(-1);
  const n = Math.floor(length / STEP_M) + 1;
  const alongs = Array.from({ length: n }, (_, i) => i * STEP_M);
  if (alongs.at(-1) < length) alongs.push(length);
  const raw = await elevations(alongs.map(a => pointAt(path, cum, a)));

  // Smooth the ground model first, so a bridge or tunnel takes its ends' heights from the smoothed line.
  const k = Math.round(SMOOTH_M / STEP_M);
  const smooth = raw.map((_, i) => {
    let s = 0, c = 0;
    for (let j = Math.max(0, i - k); j <= Math.min(raw.length - 1, i + k); j++) { s += raw[j]; c++; }
    return s / c;
  });
  const bridged = await structures(path, cum);
  const heightAt = a => {
    const f = a / STEP_M, i = Math.min(smooth.length - 2, Math.floor(f));
    return smooth[i] + (f - i) * (smooth[i + 1] - smooth[i]);
  };
  const m = alongs.map((a, i) => {
    const s = bridged.find(b => a > b.from && a < b.to);
    if (!s) return smooth[i];
    const h0 = heightAt(s.from), h1 = heightAt(s.to);
    return h0 + (h1 - h0) * (a - s.from) / (s.to - s.from);
  });

  await writeFile(join(dir, 'elevation.json'), JSON.stringify({
    spec: 'maptalk.elevation/1', routeId: id, stepM: STEP_M, lengthM: Math.round(length), source: SOURCE, attribution: ATTRIBUTION,
    structures: bridged.map(s => ({ kind: s.kind, fromM: Math.round(s.from), toM: Math.round(s.to), name: s.name })),
    m: m.map(h => Math.round(h)),
  }) + '\n');

  const lo = Math.min(...m), hi = Math.max(...m), top = m.indexOf(hi);
  console.log(`${id}: ${(length / 1000).toFixed(1)} km, ${m.length} heights, ${Math.round(m[0])} m → ${Math.round(m.at(-1))} m, highest ${Math.round(hi)} m at ${(alongs[top] / 1000).toFixed(1)} km, lowest ${Math.round(lo)} m`);
  for (const s of bridged) console.log(`  ${s.kind.padEnd(6)} ${(s.from / 1000).toFixed(2)}–${(s.to / 1000).toFixed(2)} km ${s.name}`);
}

const ids = process.argv.slice(2);
if (!ids.length) { console.error('usage: node tools/anchors/build-elevation.mjs <routeId> [...]'); process.exit(1); }
for (const id of ids) await build(id).catch(err => { console.error(`${id}: ${err.message}`); process.exitCode = 1; });
