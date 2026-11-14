/**
 * Confirms CPanel activate payload is a curated export (not full Xtream),
 * and that OwnTV-facing allowlists match overlay stream ids.
 */
const BASE = 'https://lamassucpanel.netlify.app'
const installId = `export-filter-${Date.now()}`

const res = await fetch(`${BASE}/api/v1/subscription/activate`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    'X-Install-Id': installId,
  },
  body: JSON.stringify({
    mode: 'free',
    install_id: installId,
    device_fingerprint: 'export-filter',
  }),
})
if (!res.ok) throw new Error(`HTTP ${res.status}`)
const j = await res.json()
const overlays = j.overlays || []
const perms = j.permissions || {}
const liveOverlays = overlays.filter((o) => o.content_type === 'live').map((o) => String(o.external_id))
const liveAllow = (perms.live_stream_allowlist || []).map(String)
const liveSet = new Set(liveOverlays)
const allowSet = new Set(liveAllow)

const missingInAllow = liveOverlays.filter((id) => !allowSet.has(id))
const extraInAllow = liveAllow.filter((id) => !liveSet.has(id))

const report = {
  ok:
    j.status === 'active' &&
    perms.curated === true &&
    perms.allow_live === true &&
    perms.allow_movies === false &&
    perms.allow_series === false &&
    liveOverlays.length > 0 &&
    liveOverlays.length === liveAllow.length &&
    missingInAllow.length === 0,
  overlays: overlays.length,
  liveExported: liveOverlays.length,
  liveAllowlist: liveAllow.length,
  movieClosed: (perms.movie_category_allowlist || []).includes('__none__'),
  seriesClosed: (perms.series_category_allowlist || []).includes('__none__'),
  missingInAllow: missingInAllow.length,
  extraInAllow: extraInAllow.length,
}

console.log(JSON.stringify(report, null, 2))
if (!report.ok) process.exit(1)
