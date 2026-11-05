// Writes the offline demo data next to the route assets:
//   app/src/main/assets/scenarios/local-weather.json    SY60E: weather turns at the Inje-Yangyang tunnel exit, late lane
//                                                       changes before Seoyangyang IC, hard braking at a virtual camera
//   app/src/main/assets/scenarios/rain-everywhere.json  SY60E control: rain on the whole road, nothing local
//   app/src/main/assets/scenarios/stopped-cars.json     SY60E: cars stop suddenly on open road, between facilities
//   app/src/main/assets/scenarios/hangyeryeong-fog.json N44E: hard braking at a hairpin, fog past the Namseorak tunnel,
//                                                       hard braking at a camera on the way down from the pass
//   app/src/main/assets/baseline.json                   usual values for every anchor of both routes (generated, labelled
//                                                       as simulation in the app)
// Usage: node tools/scenarios/make-scenarios.mjs   (after tools/anchors/build-anchors.mjs and build-route44.mjs)
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ASSETS = join(dirname(fileURLToPath(import.meta.url)), '../../app/src/main/assets');
const route = async id => JSON.parse(await readFile(join(ASSETS, 'routes', id, 'anchors.json'), 'utf8'));
const { anchors, lengthM } = await route('SY60E');
const n44 = await route('N44E');
const byName = (name, list = anchors) => {
  const a = list.find(x => x.name === name);
  if (!a) throw new Error(`anchor not found: ${name}`);
  return a;
};
const tunnel = byName('인제양양터널');
const ic = byName('서양양IC');
const camera = anchors.find(a => a.type === 'CAMERA' && a.alongM < tunnel.alongM);
if (!camera) throw new Error('no virtual camera west of the long tunnel');

const N = 9;
// Starts spread over half an hour, so every pass ends inside the last hour and the 1-hour window holds all nine.
const startMinutesAgo = i => 34 + i * 4;
const widths = ['MEDIUM', 'WIDE', 'MEDIUM', 'NARROW', 'MEDIUM', 'WIDE', 'MEDIUM', 'MEDIUM', 'NARROW'];

const localWeather = {
  spec: 'maptalk.scenario/1',
  id: 'local-weather',
  routeId: 'SY60E',
  title: '인제양양터널 너머 비, 서양양IC 직전 끼어들기',
  titleEn: 'Rain past the Inje-Yangyang Tunnel, late merges before W.Yangyang IC',
  description: `영서는 맑고 영동은 비. 9대 중 6대가 ${tunnel.name} 출구에서 와이퍼를 켜고 3대는 안개등도 켠다. ` +
    `4대가 ${ic.name} 300m 안에서 방향지시등을 켜고, 3대가 ${camera.name} 앞에서 급제동한다.`,
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [];
    if (i < 6) segments.push({ fromM: tunnel.endAlongM + 30, toM: lengthM, wiper: true });
    if (i < 3) segments.push({ fromM: tunnel.endAlongM + 30, toM: tunnel.endAlongM + 3000, fog: true });
    if (i >= 2 && i < 6) segments.push({ fromM: ic.alongM - 220, toM: ic.alongM + 50, turn: 'RIGHT' });
    if (i === 6 || i === 7) segments.push({ fromM: ic.alongM - 1100, toM: ic.alongM + 50, turn: 'RIGHT' }); // signalled early
    if (i % 3 === 0) {
      segments.push({ fromM: camera.alongM - 140, toM: camera.alongM + 60, kmh: 62 });
      segments.push({ fromM: camera.alongM - 150, toM: camera.alongM - 60, brake: true });
    }
    return { id: `sim-${i + 1}`, startMinutesAgo: startMinutesAgo(i), kmh: 92 + (i % 4) * 2, widthClass: widths[i], segments };
  }),
};

const rainEverywhere = {
  spec: 'maptalk.scenario/1',
  id: 'rain-everywhere',
  routeId: 'SY60E',
  title: '대조군: 전 구간 비',
  titleEn: 'Control: rain along the whole route',
  description: '9대 모두 처음부터 끝까지 와이퍼를 켠다. 도로 전체의 날씨이므로 어느 시설물도 "평소와 다름"이 되면 안 된다.',
  vehicles: Array.from({ length: N }, (_, i) => ({
    id: `sim-${i + 1}`, startMinutesAgo: startMinutesAgo(i), kmh: 88 + (i % 4) * 2, widthClass: widths[i],
    segments: [{ fromM: 0, toM: lengthM, wiper: true }],
  })),
};

// The mountain road: 9 cars at 56–62 km/h, so the hour-long trip ends before "now" and the places past the pass
// fall inside the last hour. Six switch fog lights on past the Namseorak tunnel; three brake hard at the camera on the
// way down. Nothing else is unusual, so the junctions in the valley stay usual.
const pass = {
  tunnel: byName('남설악터널', n44.anchors),
  camera: n44.anchors.find(a => a.type === 'CAMERA'),
};
if (!pass.camera) throw new Error('no camera on route 44');
// A switchback on the way down from the summit (Curvature.hairpins puts one at 32.4 km), well inside one segment.
const hairpinSegment = segmentAround(n44.anchors, 32_400);
pass.hairpinAt = Math.round((hairpinSegment.alongM + hairpinSegment.endAlongM) / 2);
const hangyeryeongFog = {
  spec: 'maptalk.scenario/1',
  id: 'hangyeryeong-fog',
  routeId: 'N44E',
  title: '한계령: 헤어핀 급제동, 남설악터널 너머 안개, 카메라 앞 급제동',
  titleEn: 'Hangyeryeong: hard braking at a hairpin, fog past the Namseorak Tunnel, braking at the camera',
  description: `고개를 넘어 내려가는 ${hairpinSegment.name} 구간 헤어핀에서 9대 중 4대가 급제동한다. ` +
    `6대가 ${pass.tunnel.name} 출구에서 안개등을 켜고, 3대가 내리막 ${pass.camera.name} 앞에서 급제동한다.`,
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [];
    if (i < 6) segments.push({ fromM: pass.tunnel.endAlongM + 30, toM: pass.tunnel.endAlongM + 2500, fog: true });
    if (i % 3 === 0) {
      segments.push({ fromM: pass.camera.alongM - 140, toM: pass.camera.alongM + 60, kmh: 32 });
      segments.push({ fromM: pass.camera.alongM - 150, toM: pass.camera.alongM - 60, brake: true });
    }
    // Between facilities: on the pass, four cars brake hard at a hairpin (no facility there, only a segment).
    if (i % 2 === 1) {
      segments.push({ fromM: pass.hairpinAt - 100, toM: pass.hairpinAt + 100, kmh: 25 });
      segments.push({ fromM: pass.hairpinAt - 110, toM: pass.hairpinAt - 20, brake: true });
    }
    return { id: `sim-${i + 1}`, startMinutesAgo: 70 + i * 3, kmh: 56 + (i % 4) * 2, widthClass: widths[i], segments };
  }),
};

// The expressway between facilities: four of nine cars stop for a while in the middle of the open stretch past
// Seomyeon 3 tunnel, braking hard as they do; the rest drive on. Nothing at any facility.
const stopSegment = segmentAround(anchors, 26_600);
const stopAt = Math.round((stopSegment.alongM + stopSegment.endAlongM) / 2);
const stoppedCars = {
  spec: 'maptalk.scenario/1',
  id: 'stopped-cars',
  routeId: 'SY60E',
  title: `${stopSegment.name} 구간: 갑자기 멈춘 차들`,
  titleEn: `${stopSegment.nameEn}: cars stopping suddenly`,
  description: `시설물이 없는 ${stopSegment.name} 구간 한가운데서 9대 중 4대가 급제동하고 잠시 멈춘다. 나머지는 그대로 지나간다.`,
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [];
    if (i % 2 === 0 && i < 8) {
      segments.push({ fromM: stopAt - 30, toM: stopAt + 5, kmh: 2 }); // wider than one second of driving, so every car lands in it
      segments.push({ fromM: stopAt - 60, toM: stopAt + 5, brake: true });
    }
    return { id: `sim-${i + 1}`, startMinutesAgo: startMinutesAgo(i), kmh: 92 + (i % 4) * 2, widthClass: widths[i], segments };
  }),
};

/** The segment anchor whose stretch holds `alongM`, so a scenario event sits inside one segment, not across two. */
function segmentAround(list, alongM) {
  const s = list.find(a => a.type === 'SEGMENT' && a.alongM <= alongM && a.endAlongM > alongM);
  if (!s) throw new Error(`no segment at ${alongM} m`);
  for (const f of list.filter(a => a.type !== 'SEGMENT')) {
    if (alongM >= f.alongM - f.zoneBeforeM && alongM <= f.endAlongM + f.zoneAfterM) throw new Error(`${alongM} m is inside ${f.id}'s zone`);
  }
  return s;
}

const usual = {
  hardBrakeRate: 0.03, lateLaneChangeRate: 0.08, wiperTurnedOnRate: 0.03, fogTurnedOnRate: 0.02,
  wiperOnRate: 0.1, entryDecelKmh: 4, stopRate: 0.05,
};
// Open road: fewer stops than at a toll gate, fog lights rarer than wipers.
const usualSegment = { hardBrakeRate: 0.03, stopRate: 0.02, wiperOnRate: 0.1, fogOnRate: 0.03 };
const baseline = {
  spec: 'maptalk.baseline/1',
  weeks: 4,
  simulated: true,
  values: Object.fromEntries([...anchors, ...n44.anchors].map(a => [a.id, Object.fromEntries(Array.from({ length: 24 }, (_, h) => [String(h), a.type === 'SEGMENT' ? usualSegment : usual]))])),
};

await mkdir(join(ASSETS, 'scenarios'), { recursive: true });
await writeFile(join(ASSETS, 'scenarios/local-weather.json'), JSON.stringify(localWeather, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/rain-everywhere.json'), JSON.stringify(rainEverywhere, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/hangyeryeong-fog.json'), JSON.stringify(hangyeryeongFog, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/stopped-cars.json'), JSON.stringify(stoppedCars, null, 2) + '\n');
await writeFile(join(ASSETS, 'baseline.json'), JSON.stringify(baseline) + '\n');
console.log(`scenarios: local-weather (${tunnel.id} exit ${tunnel.endAlongM} m, ${ic.id} ${ic.alongM} m, ${camera.id} ${camera.alongM} m), rain-everywhere, hangyeryeong-fog (${pass.tunnel.id} exit ${pass.tunnel.endAlongM} m, ${pass.camera.id} ${pass.camera.alongM} m); baseline for ${anchors.length + n44.anchors.length} anchors`);
