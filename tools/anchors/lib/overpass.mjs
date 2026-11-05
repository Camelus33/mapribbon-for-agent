// Minimal Overpass client for build-time use only. Copied from habitus33 scripts/atlas/lib/overpass.mjs.
// The public instance answers "busy" with empty or non-JSON bodies, so every failure is explicit and retried.
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export const DEFAULT_ENDPOINT = 'https://overpass-api.de/api/interpreter';
export const USER_AGENT = 'maptalk-drive-anchors/0.1 (build-time only)';

export class OverpassError extends Error {
  constructor(message, detail) { super(message); this.name = 'OverpassError'; this.detail = detail; }
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

export async function overpass(query, {
  endpoint = DEFAULT_ENDPOINT, cacheDir = null, fetchImpl = fetch, retries = 5, baseDelayMs = 8000, politeDelayMs = 4000, sleepImpl = sleep,
} = {}) {
  const id = createHash('sha256').update(query).digest('hex').slice(0, 24);
  if (cacheDir) {
    try { return JSON.parse(await readFile(join(cacheDir, `${id}.json`), 'utf8')); } catch { /* cache miss */ }
  }
  let lastProblem = 'unknown';
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (attempt) await sleepImpl(baseDelayMs * attempt);
    let res;
    try {
      res = await fetchImpl(endpoint, {
        method: 'POST',
        headers: { 'User-Agent': USER_AGENT, 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
        body: new URLSearchParams({ data: query }).toString(),
      });
    } catch (e) { lastProblem = `network: ${e.message}`; continue; }
    const text = await res.text();
    if (!res.ok) { lastProblem = `http ${res.status}`; continue; }
    let json;
    try { json = JSON.parse(text); } catch { lastProblem = `non-JSON body (${text.slice(0, 80).replace(/\s+/g, ' ')})`; continue; }
    // A runtime "remark" means a truncated/aborted result; treating it as success would drop data silently.
    if (json.remark) { lastProblem = `remark: ${json.remark}`; continue; }
    if (!Array.isArray(json.elements)) { lastProblem = 'missing elements'; continue; }
    if (cacheDir) { await mkdir(cacheDir, { recursive: true }); await writeFile(join(cacheDir, `${id}.json`), text); }
    await sleepImpl(politeDelayMs);
    return json;
  }
  throw new OverpassError(`Overpass failed after ${retries + 1} attempts: ${lastProblem}`, lastProblem);
}
