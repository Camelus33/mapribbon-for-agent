// English names for screens outside Korea. OSM name:en wins; otherwise the Korean name is romanised (Revised
// Romanisation, syllable by syllable, no sound-change rules) and the facility word translated. A few names have a
// published English form that romanisation cannot guess; they are listed here.
export const ENGLISH_OVERRIDES = { '인제양양터널': 'Inje-Yangyang Tunnel', '렛츠런파크교차로': "Let's Run Park Junction", '1100로': '1100 Road', '평화로': 'Pyeonghwa-ro' };
const SUFFIXES = [['터널', 'Tunnel'], ['분기점', 'JC'], ['나들목', 'IC'], ['요금소', 'Tollgate'], ['영업소', 'Tollgate'], ['교차로', 'Junction'], ['삼거리', 'Junction'], ['사거리', 'Crossroad'], ['오거리', 'Crossroad'], ['입구', 'Entrance'], ['IC', 'IC'], ['JC', 'JC'], ['TG', 'TG']];
const INITIAL = ['g', 'kk', 'n', 'd', 'tt', 'r', 'm', 'b', 'pp', 's', 'ss', '', 'j', 'jj', 'ch', 'k', 't', 'p', 'h'];
const MEDIAL = ['a', 'ae', 'ya', 'yae', 'eo', 'e', 'yeo', 'ye', 'o', 'wa', 'wae', 'oe', 'yo', 'u', 'wo', 'we', 'wi', 'yu', 'eu', 'ui', 'i'];
const FINAL = ['', 'k', 'k', 'k', 'n', 'n', 'n', 't', 'l', 'k', 'm', 'l', 'l', 'l', 'p', 'l', 'm', 'p', 'p', 't', 't', 'ng', 't', 't', 'k', 't', 'p', 't'];

function romanise(text) {
  let out = '';
  for (const ch of text) {
    const code = ch.codePointAt(0) - 0xac00;
    if (code < 0 || code > 11171) { out += ch; continue; }
    out += INITIAL[Math.floor(code / 588)] + MEDIAL[Math.floor((code % 588) / 28)] + FINAL[code % 28];
  }
  return out.charAt(0).toUpperCase() + out.slice(1);
}

export function english(name) {
  if (ENGLISH_OVERRIDES[name]) return ENGLISH_OVERRIDES[name];
  const [ko, en] = SUFFIXES.find(([k]) => name.endsWith(k)) ?? ['', ''];
  const stem = name.slice(0, name.length - ko.length);
  const m = stem.match(/^(.*?)(\d+)$/);
  const words = m ? [romanise(m[1]), m[2]] : [romanise(stem)];
  return [...words, en].filter(Boolean).join(' ');
}
