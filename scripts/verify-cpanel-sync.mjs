/**
 * Live CPanel entitlement probe for OwnTV sync (no Android JDK required).
 * Validates activate/validate deliver overlays with stream_url for Room seeding.
 *
 * Usage: node scripts/verify-cpanel-sync.mjs
 */
import assert from 'node:assert/strict'

const BASE = 'https://lamassucpanel.netlify.app'
const installId = `owntv-verify-${Date.now()}`

async function post(path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'X-Install-Id': installId,
    },
    body: JSON.stringify(body),
  })
  const text = await res.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    throw new Error(`${path} non-JSON HTTP ${res.status}: ${text.slice(0, 200)}`)
  }
  return { status: res.status, json }
}

async function get(path) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { Accept: 'application/json', 'Cache-Control': 'no-cache' },
  })
  const json = await res.json()
  return { status: res.status, json }
}

const config = await get('/api/v1/config')
assert.equal(config.status, 200)
assert.ok(config.json.app_name, 'config.app_name')

const banners = await get('/api/v1/banners')
assert.equal(banners.status, 200)
assert.ok(Array.isArray(banners.json.banners), 'banners[]')

const activate = await post('/api/v1/subscription/activate', {
  mode: 'free',
  install_id: installId,
  device_fingerprint: 'verify-script',
  app_version_code: 1,
  app_version_name: 'verify',
})
assert.equal(activate.status, 200, `activate HTTP ${activate.status}`)
assert.equal(activate.json.status, 'active')
assert.ok(activate.json.provider?.host, 'provider.host')
assert.ok(activate.json.provider?.username, 'provider.username')

const overlays = activate.json.overlays || []
assert.ok(overlays.length > 0, 'overlays must be non-empty for curated free mode')
const live = overlays.filter((o) => o.content_type === 'live')
const withUrl = live.filter((o) => String(o.stream_url || '').trim())
assert.ok(live.length > 0, 'live overlays')
assert.equal(withUrl.length, live.length, 'every live overlay needs stream_url for OwnTV seeder')

const validate = await post('/api/v1/subscription/validate', {
  mode: 'free',
  install_id: installId,
})
assert.equal(validate.status, 200)
assert.ok((validate.json.overlays || []).length > 0, 'validate overlays')

console.log(
  JSON.stringify(
    {
      ok: true,
      app: config.json.app_name,
      banners: banners.json.banners.length,
      activateOverlays: overlays.length,
      liveWithStreamUrl: withUrl.length,
      provider: activate.json.provider.host,
      allowLive: activate.json.permissions?.allow_live,
      allowMovies: activate.json.permissions?.allow_movies,
      allowSeries: activate.json.permissions?.allow_series,
    },
    null,
    2,
  ),
)
