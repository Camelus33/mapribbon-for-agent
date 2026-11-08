// Writes the offline demo data next to the route assets:
//   app/src/main/assets/scenarios/local-weather.json    SY60E: weather turns at the Inje-Yangyang tunnel exit, late lane
//                                                       changes before Seoyangyang IC, hard braking at a virtual camera
//   app/src/main/assets/scenarios/rain-everywhere.json  SY60E control: rain on the whole road, nothing local
//   app/src/main/assets/scenarios/stopped-cars.json     SY60E: cars stop suddenly on open road, between facilities
//   app/src/main/assets/scenarios/hangyeryeong-sleet.json N44E: sleet at 1 °C over the summit, so possible ice there
//   app/src/main/assets/scenarios/hangyeryeong-fog.json N44E: hard braking at a hairpin, fog past the Namseorak tunnel,
//                                                       hard braking at a camera on the way down from the pass
//   app/src/main/assets/scenarios/jeju-1100-ice.json    J1100S: sleet at 0 °C over the 1100 m summit, 7 °C in Jeju City
//   app/src/main/assets/scenarios/jeju-1100-fog.json    J1100S: hard braking at the Eorimok hairpin, fog past the Yeongsil
//                                                       turn-off, braking at a camera on the 40 km/h descent
//   app/src/main/assets/scenarios/jeju-1100-sleet-everywhere.json  J1100S control: sleet at 0 °C over the whole road, nothing local
//   app/src/main/assets/scenarios/jeju-pyeonghwa.json   J1135S: cars stopping at the Gwangpyeong signal, braking at a camera,
//                                                       late lane changes before the Seogwang 1 turn-off
//   app/src/main/assets/baseline.json                   usual values for every anchor of all routes (generated, labelled
//                                                       as simulation in the app)
// Usage: node tools/scenarios/make-scenarios.mjs   (after tools/anchors/build-anchors.mjs, build-route44.mjs, build-jeju.mjs)
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ASSETS = join(dirname(fileURLToPath(import.meta.url)), '../../app/src/main/assets');
const route = async id => JSON.parse(await readFile(join(ASSETS, 'routes', id, 'anchors.json'), 'utf8'));
const { anchors, lengthM } = await route('SY60E');
const n44 = await route('N44E');
const j1100 = await route('J1100S');
const j1135 = await route('J1135S');
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

// A downpour on one open stretch past the tunnel: four of the six cars in the rain switch to fast wipers and low beams.
const downpour = segmentAround(anchors, 26_600);
const localWeather = {
  spec: 'maptalk.scenario/1',
  id: 'local-weather',
  routeId: 'SY60E',
  title: '인제양양터널 너머 비와 폭우, 서양양IC 직전 끼어들기',
  titleEn: 'Rain and a downpour past the Inje-Yangyang Tunnel, late merges before W.Yangyang IC',
  description: `영서는 맑고 영동은 비. 9대 중 6대가 ${tunnel.name} 출구에서 와이퍼를 켜고, 그중 4대는 ${downpour.name} 구간에서 와이퍼를 빠르게 돌리고 전조등을 켠다. ` +
    `4대가 ${ic.name} 300m 안에서 방향지시등을 켜고, 3대가 ${camera.name} 앞에서 급제동한다.`,
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [];
    if (i < 6) segments.push({ fromM: tunnel.endAlongM + 30, toM: lengthM, wiper: true });
    if (i < 4) segments.push({ fromM: downpour.alongM + 50, toM: downpour.endAlongM - 50, wiperLevel: 'HIGH', lowBeam: true });
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
  title: '한계령: 헤어핀 급제동, 남설악터널 너머 짙은 안개, 카메라 앞 급제동',
  titleEn: 'Hangyeryeong: hard braking at a hairpin, thick fog past the Namseorak Tunnel, braking at the camera',
  description: `고개를 넘어 내려가는 ${hairpinSegment.name} 구간 헤어핀에서 9대 중 4대가 급제동한다. ` +
    `6대가 ${pass.tunnel.name} 출구에서 안개등을 켜고(4대는 뒤 안개등까지), 3대가 내리막 ${pass.camera.name} 앞에서 급제동한다.`,
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [];
    if (i < 6) segments.push({ fromM: pass.tunnel.endAlongM + 30, toM: pass.tunnel.endAlongM + 2500, fog: true });
    if (i < 4) segments.push({ fromM: pass.tunnel.endAlongM + 30, toM: pass.tunnel.endAlongM + 2500, rearFog: true }); // thick: rear fog lights too
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

// The summit in November: 6 °C in the valleys, 1 °C on the two segments over the top of the pass, where seven of nine
// cars run their wipers in sleet and their low beams come on. Wet road near freezing is ice; nothing else is unusual.
const summit = [segmentAround(n44.anchors, 28_500), segmentAround(n44.anchors, 30_500)];
const hangyeryeongSleet = {
  spec: 'maptalk.scenario/1',
  id: 'hangyeryeong-sleet',
  routeId: 'N44E',
  title: '한계령 정상 부근 진눈깨비: 결빙 가능성',
  titleEn: 'Sleet near the Hangyeryeong summit: possible ice',
  description: `골짜기는 6°C, 고개 마루인 ${summit[0].name}–${summit[1].name} 구간은 1°C. ` +
    '그 구간에서 9대 중 7대가 와이퍼를 켜고 전조등이 켜진다. 젖은 노면이 어는점 근처라 결빙 가능성으로 읽힌다.',
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [{ fromM: summit[0].alongM, toM: summit[1].endAlongM, tempC: 1 }];
    if (i < 7) segments.push({ fromM: summit[0].alongM + 40, toM: summit[1].endAlongM - 40, wiperLevel: 'INTERMITTENT', lowBeam: true });
    // The summit is half an hour in: these starts put every pass over it inside the last hour.
    return { id: `sim-${i + 1}`, startMinutesAgo: 50 + i * 3, kmh: 56 + (i % 4) * 2, widthClass: widths[i], tempC: 6, segments };
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


// ---------------------------------------------------------------- Jeju: the 1100 Road over Hallasan and Pyeonghwa-ro
// Both trips go from Nohyeong in Jeju City to Jungmun. The 1100 Road climbs to 1,100 m: Jeju has closed it for snow and
// ice most winters (its hairpins below Eoseungsaeng and the 40 km/h descent past Yeongsil are where the island lowered
// the limits in 2017 after rental-car crashes). Pyeonghwa-ro is the fast 80 km/h road around the mountain, with
// at-grade signal junctions where the queue starts suddenly.
const mtn = {
  eorimok: byName('어리목입구교차로', j1100.anchors),
  yeongsil: byName('영실입구삼거리', j1100.anchors),
  camera: j1100.anchors.find(a => a.type === 'CAMERA'),
};
if (!mtn.camera) throw new Error('no camera on the 1100 Road');
// The hairpin above Eorimok (Curvature.hairpins puts one at 13.5 km), inside one segment, past the junction's zone.
const eorimokHairpin = segmentAround(j1100.anchors, 13_525);
mtn.hairpinAt = 13_525;
const mountainSpeed = i => 42 + (i % 4) * 2; // 34 km in about 47 minutes

// Sleet over the summit: 7 °C in the city, 0 °C on the two segments around the 1100 m pass (the rest area sits at
// 18.6 km), where seven of nine cars run their wipers and their low beams come on. Wet road at freezing is ice.
const summitJeju = [segmentAround(j1100.anchors, 17_000), segmentAround(j1100.anchors, 19_000)];
const jeju1100Ice = {
  spec: 'maptalk.scenario/1',
  id: 'jeju-1100-ice',
  routeId: 'J1100S',
  title: '1100고지 진눈깨비: 결빙 가능성',
  titleEn: 'Sleet at the 1100 m summit: possible ice',
  description: `제주시는 7°C, 1100고지 양쪽 ${summitJeju[0].name}–${summitJeju[1].name} 구간은 0°C. ` +
    '그 구간에서 9대 중 7대가 와이퍼를 켜고 전조등이 켜진다. 젖은 노면이 어는점이라 결빙 가능성으로 읽힌다.',
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [{ fromM: summitJeju[0].alongM, toM: summitJeju[1].endAlongM, tempC: 0 }];
    if (i < 7) segments.push({ fromM: summitJeju[0].alongM + 40, toM: summitJeju[1].endAlongM - 40, wiperLevel: 'INTERMITTENT', lowBeam: true });
    // The summit is 24 minutes in: these starts put every pass over it inside the last hour.
    return { id: `sim-${i + 1}`, startMinutesAgo: 48 + i * 3, kmh: mountainSpeed(i), widthClass: widths[i], tempC: 7, segments };
  }),
};

// Fog on the way down: six of nine switch fog lights on just past the Yeongsil turn-off and keep them on for three
// kilometres (four with rear fog lights too); four brake hard at the hairpin above Eorimok; three brake at the camera
// on the 40 km/h descent.
const fogFrom = mtn.yeongsil.alongM + mtn.yeongsil.zoneAfterM + 60, fogTo = mtn.camera.alongM - mtn.camera.zoneBeforeM - 600; // ends inside the segment before the camera's
const jeju1100Fog = {
  spec: 'maptalk.scenario/1',
  id: 'jeju-1100-fog',
  routeId: 'J1100S',
  title: '1100도로: 어리목 헤어핀 급제동, 영실 너머 짙은 안개, 카메라 앞 급제동',
  titleEn: '1100 Road: hard braking at the Eorimok hairpin, thick fog past Yeongsil, braking at the camera',
  description: `${eorimokHairpin.name} 구간 헤어핀에서 9대 중 4대가 급제동한다. ` +
    `6대가 ${mtn.yeongsil.name}를 지나자마자 안개등을 켜고(4대는 뒤 안개등까지) 3 km를 그대로 간다. 3대가 내리막 ${mtn.camera.name} 앞에서 급제동한다.`,
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [];
    if (i < 6) segments.push({ fromM: fogFrom, toM: fogTo, fog: true });
    if (i < 4) segments.push({ fromM: fogFrom, toM: fogTo, rearFog: true });
    if (i % 3 === 0) {
      segments.push({ fromM: mtn.camera.alongM - 140, toM: mtn.camera.alongM + 60, kmh: 18 });
      segments.push({ fromM: mtn.camera.alongM - 150, toM: mtn.camera.alongM - 60, brake: true });
    }
    if (i % 2 === 1) {
      segments.push({ fromM: mtn.hairpinAt - 100, toM: mtn.hairpinAt + 100, kmh: 20 });
      segments.push({ fromM: mtn.hairpinAt - 110, toM: mtn.hairpinAt - 20, brake: true });
    }
    return { id: `sim-${i + 1}`, startMinutesAgo: 52 + i * 3, kmh: mountainSpeed(i), widthClass: widths[i], segments };
  }),
};

// Control: sleet over the whole 1100 Road. Every car reports 0 °C from Nohyeong to Jungmun and runs its wipers the
// whole way, so the ice signal is the same everywhere; a driver already knows, and nothing may be called different.
const jeju1100Sleet = {
  spec: 'maptalk.scenario/1',
  id: 'jeju-1100-sleet-everywhere',
  routeId: 'J1100S',
  title: '대조군: 1100도로 전 구간 진눈깨비',
  titleEn: 'Control: sleet along the whole 1100 Road',
  description: '9대 모두 노형에서 중문까지 0°C를 보고하고 처음부터 끝까지 와이퍼를 켠다. 도로 전체의 날씨이므로 어느 곳도 "평소와 다름"이 되면 안 된다.',
  vehicles: Array.from({ length: N }, (_, i) => ({
    id: `sim-${i + 1}`, startMinutesAgo: 52 + i * 3, kmh: mountainSpeed(i), widthClass: widths[i], tempC: 0,
    segments: [{ fromM: 0, toM: j1100.lengthM, wiperLevel: 'INTERMITTENT', lowBeam: true }],
  })),
};

// Pyeonghwa-ro: the queue at the Gwangpyeong signal (an at-grade junction on an 80 km/h road) backs up past the
// junction's own zone, so four of nine stop on the open road 600 m before it; three brake at the second camera, and
// four signal late before the Seogwang 1 turn-off where the trip leaves for Jungmun.
const flat = {
  signal: byName('광평교차로', j1135.anchors),
  camera: j1135.anchors.filter(a => a.type === 'CAMERA')[1],
  turnoff: byName('서광1교차로', j1135.anchors),
};
if (!flat.camera) throw new Error('no second camera on Pyeonghwa-ro');
flat.queueAt = flat.signal.alongM - flat.signal.zoneBeforeM - 220;
flat.queueSegment = segmentAround(j1135.anchors, flat.queueAt);
const jejuPyeonghwa = {
  spec: 'maptalk.scenario/1',
  id: 'jeju-pyeonghwa',
  routeId: 'J1135S',
  title: '평화로: 광평교차로 신호 대기 정체, 카메라 앞 급제동, 서광1교차로 직전 끼어들기',
  titleEn: 'Pyeonghwa-ro: a queue at the Gwangpyeong signal, braking at the camera, late merges before Seogwang 1',
  description: `시속 80 km 도로의 평면 교차로인 ${flat.signal.name} 신호 대기 줄이 600 m 뒤 ${flat.queueSegment.name} 구간까지 이어져, 9대 중 4대가 거기서 급제동하고 멈춘다. ` +
    `3대가 ${flat.camera.name} 앞에서 급제동하고, 4대가 중문 쪽으로 꺾는 ${flat.turnoff.name} 300 m 안에서 방향지시등을 켠다.`,
  vehicles: Array.from({ length: N }, (_, i) => {
    const segments = [];
    if (i % 2 === 0 && i < 8) {
      segments.push({ fromM: flat.queueAt - 30, toM: flat.queueAt + 5, kmh: 2 });
      segments.push({ fromM: flat.queueAt - 60, toM: flat.queueAt + 5, brake: true });
    }
    if (i % 3 === 0) {
      segments.push({ fromM: flat.camera.alongM - 140, toM: flat.camera.alongM + 60, kmh: 50 });
      segments.push({ fromM: flat.camera.alongM - 150, toM: flat.camera.alongM - 60, brake: true });
    }
    if (i >= 2 && i < 6) segments.push({ fromM: flat.turnoff.alongM - 220, toM: flat.turnoff.alongM + 50, turn: 'RIGHT' });
    if (i === 6 || i === 7) segments.push({ fromM: flat.turnoff.alongM - 1100, toM: flat.turnoff.alongM + 50, turn: 'RIGHT' }); // signalled early
    return { id: `sim-${i + 1}`, startMinutesAgo: 36 + i * 4, kmh: 78 + (i % 4) * 2, widthClass: widths[i], segments };
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

// What each scenario is meant to show, for its card in the demo panel: which metric (drawn as its icon), where, in how
// many cars. DemoRouteTest holds the ribbons to these.
localWeather.story = [
  { metric: 'hardBrakeRate', fromM: camera.alongM, cars: 3 },
  { metric: 'wiperTurnedOnRate', fromM: tunnel.endAlongM, cars: 6 },
  { metric: 'lateLaneChangeRate', fromM: ic.alongM, cars: 4 },
  { metric: 'wiperFastRate', fromM: downpour.alongM, toM: downpour.endAlongM, cars: 4 },
];
rainEverywhere.story = [{ metric: 'wiperOnRate', fromM: 0, toM: lengthM, cars: N, expectDifferent: false }];
stoppedCars.story = [{ metric: 'stopRate', fromM: stopAt, cars: 4 }];
hangyeryeongFog.story = [
  { metric: 'hardBrakeRate', fromM: pass.hairpinAt, cars: 4 },
  { metric: 'hardBrakeRate', fromM: pass.camera.alongM, cars: 3 },
  { metric: 'fogTurnedOnRate', fromM: pass.tunnel.endAlongM, cars: 6 },
  { metric: 'rearFogRate', fromM: pass.tunnel.endAlongM, toM: pass.tunnel.endAlongM + 2500, cars: 4 },
];
hangyeryeongSleet.story = [{ metric: 'iceRiskRate', fromM: summit[0].alongM, toM: summit[1].endAlongM, cars: 7 }];
jeju1100Ice.story = [{ metric: 'iceRiskRate', fromM: summitJeju[0].alongM, toM: summitJeju[1].endAlongM, cars: 7 }];
jeju1100Fog.story = [
  { metric: 'hardBrakeRate', fromM: mtn.hairpinAt, cars: 4 },
  { metric: 'hardBrakeRate', fromM: mtn.camera.alongM, cars: 3 },
  { metric: 'fogOnRate', fromM: fogFrom, toM: fogTo, cars: 6 },
  { metric: 'rearFogRate', fromM: fogFrom, toM: fogTo, cars: 4 },
];
jeju1100Sleet.story = [{ metric: 'iceRiskRate', fromM: 0, toM: j1100.lengthM, cars: N, expectDifferent: false }];
jejuPyeonghwa.story = [
  { metric: 'stopRate', fromM: flat.queueAt, cars: 4 },
  { metric: 'hardBrakeRate', fromM: flat.camera.alongM, cars: 3 },
  { metric: 'lateLaneChangeRate', fromM: flat.turnoff.alongM, cars: 4 },
];

const usual = {
  hardBrakeRate: 0.03, lateLaneChangeRate: 0.08, wiperTurnedOnRate: 0.03, fogTurnedOnRate: 0.02,
  wiperOnRate: 0.1, entryDecelKmh: 4, stopRate: 0.05,
};
// Open road: fewer stops than at a toll gate, fog lights rarer than wipers.
const usualSegment = {
  hardBrakeRate: 0.03, stopRate: 0.02, wiperOnRate: 0.1, fogOnRate: 0.03,
  wiperFastRate: 0.02, rearFogRate: 0.01, dayLowBeamRate: 0.05, iceRiskRate: 0.01,
};
const baseline = {
  spec: 'maptalk.baseline/1',
  weeks: 4,
  simulated: true,
  values: Object.fromEntries([...anchors, ...n44.anchors, ...j1100.anchors, ...j1135.anchors].map(a => [a.id, Object.fromEntries(Array.from({ length: 24 }, (_, h) => [String(h), a.type === 'SEGMENT' ? usualSegment : usual]))])),
};

await mkdir(join(ASSETS, 'scenarios'), { recursive: true });
await writeFile(join(ASSETS, 'scenarios/local-weather.json'), JSON.stringify(localWeather, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/rain-everywhere.json'), JSON.stringify(rainEverywhere, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/hangyeryeong-fog.json'), JSON.stringify(hangyeryeongFog, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/stopped-cars.json'), JSON.stringify(stoppedCars, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/hangyeryeong-sleet.json'), JSON.stringify(hangyeryeongSleet, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/jeju-1100-ice.json'), JSON.stringify(jeju1100Ice, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/jeju-1100-fog.json'), JSON.stringify(jeju1100Fog, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/jeju-1100-sleet-everywhere.json'), JSON.stringify(jeju1100Sleet, null, 2) + '\n');
await writeFile(join(ASSETS, 'scenarios/jeju-pyeonghwa.json'), JSON.stringify(jejuPyeonghwa, null, 2) + '\n');
await writeFile(join(ASSETS, 'baseline.json'), JSON.stringify(baseline) + '\n');
console.log(`scenarios: local-weather (${tunnel.id} exit ${tunnel.endAlongM} m, ${ic.id} ${ic.alongM} m, ${camera.id} ${camera.alongM} m), rain-everywhere, hangyeryeong-fog (${pass.tunnel.id} exit ${pass.tunnel.endAlongM} m, ${pass.camera.id} ${pass.camera.alongM} m); jeju-1100-ice (${summitJeju.map(x => x.id)}), jeju-1100-fog (${eorimokHairpin.id}, ${mtn.camera.id}), jeju-1100-sleet-everywhere, jeju-pyeonghwa (${flat.signal.id}, ${flat.camera.id}, ${flat.turnoff.id}); baseline for ${anchors.length + n44.anchors.length + j1100.anchors.length + j1135.anchors.length} anchors`);
