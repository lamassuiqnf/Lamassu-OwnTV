/**
 * Compare CPanel free-mode export shape that AnotherIPTV and Lamassu OwnTV both consume.
 * Confirms activate payload allowlists + overlays are consistent (no app_id filter).
 */
const BASE = 'https://lamassucpanel.netlify.app'
const installId = `parity-${Date.now()}`

async function activate(label) {
  const res = await fetch(`${BASE}/api/v1/subscription/activate`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'X-Install-Id': `${installId}-${label}`,
      'User-Agent': label === 'owntv' ? 'Lamassu/1.0' : 'AnotherIPTV/1.0',
    },
    body: JSON.stringify({
      mode: 'free',
      install_id: `${installId}-${label}`,
      device_fingerprint: `parity-${label}`,
      app_version_code: 1,
      app_version_name: 'parity',
    }),
  })
  if (!res.ok) throw new Error(`${label} HTTP ${res.status}`)
  return res.json()
}

function summarize(j) {
  const overlays = j.overlays || []
  const perms = j.permissions || {}
  const byType = (t) => overlays.filter((o) => String(o.content_type).toLowerCase() === t)
  const ids = (rows) => rows.map((o) => String(o.external_id)).sort()
  const allow = (key) => (perms[key] || []).map(String)
  const live = byType('live')
  const movie = byType('movie')
  const series = byType('series')
  const cats = byType('category')
  const liveWithUrl = live.filter((o) => String(o.stream_url || '').trim()).length
  return {
    status: j.status,
    mode: j.mode,
    curated: perms.curated === true,
    allowLive: perms.allow_live === true,
    allowMovies: perms.allow_movies === true,
    allowSeries: perms.allow_series === true,
    overlays: overlays.length,
    live: live.length,
    liveWithUrl,
    movie: movie.length,
    series: series.length,
    categories: cats.length,
    liveAllow: allow('live_stream_allowlist').length,
    liveCatAllow: allow('live_category_allowlist').filter((x) => x !== '__none__').length,
    movieClosed: allow('movie_category_allowlist').includes('__none__'),
    seriesClosed: allow('series_category_allowlist').includes('__none__'),
    liveIdsMatchAllow:
      JSON.stringify(ids(live)) ===
      JSON.stringify(allow('live_stream_allowlist').slice().sort()),
    sampleLive: live.slice(0, 3).map((o) => ({
      id: String(o.external_id),
      title: o.custom_title,
      hasUrl: !!String(o.stream_url || '').trim(),
      cat: o.category_external_id || o.meta_category_id || null,
    })),
    providerHost: j.provider?.host || null,
  }
}

const [a, b] = await Promise.all([activate('anotheriptv'), activate('owntv')])
const sa = summarize(a)
const sb = summarize(b)

const same =
  sa.live === sb.live &&
  sa.movie === sb.movie &&
  sa.series === sb.series &&
  sa.categories === sb.categories &&
  sa.liveAllow === sb.liveAllow &&
  sa.allowLive === sb.allowLive &&
  sa.allowMovies === sb.allowMovies &&
  sa.allowSeries === sb.allowSeries &&
  sa.movieClosed === sb.movieClosed &&
  sa.seriesClosed === sb.seriesClosed &&
  sa.liveIdsMatchAllow &&
  sb.liveIdsMatchAllow &&
  sa.live > 0

const report = { ok: same, anotheriptv: sa, owntv: sb }
console.log(JSON.stringify(report, null, 2))
if (!same) process.exit(1)
