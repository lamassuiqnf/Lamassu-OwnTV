# Lamassu OwnTV fork

GPLv3 fork of [OwnTV](https://github.com/ahXN00/OwnTV) wired to the Lamassu CPanel
(`https://lamassucpanel.netlify.app`).

## What changed

- `applicationId`: `com.iq.lamassu.owntv` (namespace stays `tv.own.owntv`)
- App display name: **Lamassu** / **لماسو**
- Launch gate: activate free / subscription code before OwnTV shell
- Managed Xtream provision via OwnTV `SourceImporter` after CPanel activate/validate
- BuildConfig: `LAMASSU_API_BASE_URL`

## Build

App source tracks ahead of published `tv.own.owntv:core:1.0.69`. Build against
local [OwnTV_Core](https://github.com/ahXN00/OwnTV_Core) via composite:

```bash
# ~/.gradle/gradle.properties
owntv.corePath=/absolute/path/to/OwnTV_Core
```

Copy `local.properties` (sdk.dir) into that OwnTV_Core checkout as well.

On Arabic-locale machines, force English JVM locale so Room KSP emits ASCII digits:

```bash
export JAVA_HOME=…/jdk-21
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
export GRADLE_OPTS="-Duser.language=en -Duser.country=US"
./gradlew :app:assembleStandardDebug
```

APK: `app/build/outputs/apk/standard/debug/`

## Branding & shell (applied)

- Colors: `#19192A` page + `#FF9500` accent (gradient navy wash)
- Nav: Home / Live / Movies / Series / More only (no Search, Sports, TV Guide, Downloads)
- Single **Lamassu** profile with logo avatar (no primary/secondary chooser)
- History removed from More + Live/Movies/Series category rails
- About rewritten for Lamassu + Telegram `t.me/alnafawa`
- Languages: Arabic + English in the picker
- Home banners/sections from `GET /api/v1/banners`
- Content overlays parsed from activate/validate into `ContentOverlayStore`

## Still open

- Apply overlay custom titles/logos/locks across every grid row (store is ready)
- Managed-only source UI lock (hide Add Source when managed)
- Dashboard refresh that re-validates + re-syncs catalog
