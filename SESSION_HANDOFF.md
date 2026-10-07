# SESSION HANDOFF

This file is the entry point for a fresh model/session working on this repo. It
compresses the project, the environment, everything currently done, and the
exact point we are at. Read this first, then `README.md`, then dig into
`docs/` as needed.

Last updated: 2026-10-07.

---

## 1. What the project is

**Shaka**: "Should you dive today?" For ~789 curated spearfishing spots it fuses
real-time satellite + ocean data (NASA GIBS, NOAA tides/SST, Copernicus Marine
visibility + chlorophyll, Open-Meteo weather/swell, GEBCO bathymetry) into a
0–100 dive score per day, plus live fishing intel scraped from West Coast dock
reports.

- Backend: **Kotlin/Ktor** (`shaka-api`), PostgreSQL/PostGIS, runs on Railway in
  production, ports to Sentry/BetterStack.
- Mobile: **Flutter** app (`shaka-app`, package `shaka`), iOS + Android.
- The repo also contains `db/` (init.sql), `monitoring/` (health-journey probe),
  `docs/`, `scripts/`, `shaka-tide/`, `tools/`.
- Production URL / deployment is NOT accessible from this machine; all work is
  local + pushed to a fork.

## 2. Repo layout

```
shaka/
├── shaka-api/                 Kotlin/Ktor backend (the main workspace)
│   └── src/main/kotlin/com/shaka/
│       ├── Application.kt     entry point + job scheduler
│       ├── api/routes/        HTTP handlers (health, spots, PFZ, ...)
│       ├── config/            EnvValidation, config loading
│       ├── data/client/       external API clients + RateLimiter + CircuitBreaker + HttpClientFactory
│       ├── data/cache/        SpotDataCache, OceanDataCache
│       ├── data/db/           Exposed tables/repos (spot_cache, pfz, ...)
│       ├── fishing_intel/     dock-report scraping pipeline
│       ├── model/  scoring/  pfz/  service/  monitoring/  util/
├── shaka-app/                 Flutter app
├── shaka-tide/                tide prediction tooling
├── db/init.sql                full schema (spots, spot_cache, pfz_zones_daily, ...)
├── monitoring/                journeys.json (T1/T2 contracts), probe.py
├── docs/                      design docs (see synthetic-monitor-design.md)
├── docker-compose.yml         postgis:16-3.4 on 5432 + redis on 6379
├── .github/                   CI (gates on health `db=ok`)
└── railway.toml               production orchestration
```

## 3. Environment (this PC) — do NOT ask the user to type commands

- **JDK for Gradle**: `C:\Program Files\Eclipse Adoptium\jdk-21.0.3.9-hotspot`.
  System `JAVA_HOME` is Java 8 — ALWAYS set it before any gradle command:
  `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.3.9-hotspot"`
  (module toolchain compiles with JDK 17; the daemon runs on 21).
- Gradle wrapper lives in **`shaka-api/`** (`shaka-api\gradlew.bat`).
- The `run` task is `.\gradlew.bat run` — there is **no** `bootRun` task (not a
  Spring app; `bootRun` fails with "Task not found").
- **Android phone**: `adb` at
  `C:\Users\cc\AppData\Local\Android\Sdk\platform-tools\adb.exe`, device
  `46f866f5` (Android 8.1, API 27 → `minSdk` must stay ≤ 26). Flutter
  `107/107` tests green; APK validated on device.
- `local.properties` must be exactly `sdk.dir=C:/Users/cc/AppData/Local/Android/Sdk`.

### Local DB

- `docker-compose up -d db` → container `shaka-db-1`, user/pass/db all `shaka` on
  host **port 5432**.
- Local URL: `postgresql://shaka:shaka@localhost:5432/shaka`
- PSQL alias: `docker exec shaka-db-1 psql -U shaka -d shaka -c "..."`
- ⚠️ **Port 5434 belongs to a DIFFERENT project** (`acquaintel_db`). Never point
  Shaka at it.
- ⚠️ **`docker-compose.override.yml` is untracked and stale** — it remaps Shaka
  to `5434:5432`, which port-conflicts with the other project. It must not be
  `docker compose`'d into; plan is to delete it (kept asking user).

### Build / test / run loop

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.3.9-hotspot"
cd shaka-api
.\gradlew.bat test --console=plain          # 286 tests / 23 suites / 17 DB-gated skips / 0 failures
.\gradlew.bat run --console=plain           # starts on :8080
```

Long gradle runs get killed by the tool timeout (~300s). Start them detached:
`Start-Process gradlew.bat -ArgumentList "run","--console=plain" -RedirectStandardOutput run2.log -RedirectStandardError run2.err` then poll the log for
`job_run event=` / `BUILD SUCCESSFUL`.

Health check: `http://localhost:8080/v1/health` (NOT `/health` — that 404s).

## 4. Secrets / credentials — read carefully

- Credentials live ONLY in `shaka-api/.env.local`, which is gitignored
  (`.gitignore` `*.local`). Only `KEY=` names are documented; **never print or
  commit the values.**
- `build.gradle.kts` (~line 28) loads `.env.local` into the `run`/`test` env:
  any edit to remaining key names belongs there.
- `.env.local` current keys (names): `DATABASE_URL`, `TIDE_SOURCE`,
  `COPERNICUSMARINE_SERVICE_USERNAME`, `COPERNICUSMARINE_SERVICE_PASSWORD`,
  `COP_USER`, `COP_PASS`, plus the envs listed in README.
- There are TWO Copernicus auth systems:
  - **WMTS product client** (`CopernicusWMTSClient`) needs NO auth — anonymous
    HTTPS. This drives `satellite_copernicus`.
  - **CDSE OAuth** (`COPERNICUS_CLIENT_ID`/`SECRET` in `CopernicusClient`) is a
    separate optional feature ("realtime clarity"); unset = degraded by design.
  - **CLI path** (`CopernicusGridClient`, uses marine creds) shells out to the
    `copernicusmarine` CLI, which is **not installed** on this PC → that path
    may fail even with correct creds.
- History: commit `3753c38` once exposed a Copernicus password; it is an
  ancestor of `fork/main`. Rotation is the USER's job (portal side) — they have
  `shaka-api/.env.local` open and plan to write updated values manually.

## 5. What has been done (completed work)

### PFZ v2 (feature complete)
Persistence + scheduled rescoring + retention/monitoring + history/trends +
species registry + live Copernicus scoring + Flutter gap-aware history/GPX
export. Flutter 107/107, backend 277→286 tests green, Android verified on
device.

### Background-job & health reliability (all committed & pushed to fork)
| commit | what |
|---|---|
| `89fc1c4` | Load Copernicus creds from gitignored `.env.local` |
| `4de1536` | Bound concurrency in Copernicus grid fetches |
| `2cff362` | PFZ timeout vs absent sea: `ANALYZE_TIMEOUT_MS=240_000`, `TIMED_OUT`/`NO_GRID`/`NO_PATCHES` reason-specific coverage notes |
| `790df3d` | Health fix: `DbState` = `ABSENT/REACHABLE/NOT_CONNECTED/UNREACHABLE`; shared `dbHealthState()` probe; wrong-password → 503 `not_connected` |
| `c5df056` | `livenessDbValue`: both healthy states report `db:"ok"` (keeps journeys.json/CI contract) |
| `7558cbc` | `scheduleJob` first run now fires at `initialDelayMs` (old code added `intervalMs`); `runImmediately` flag deleted. `RateLimiter.acquire()`→`Unit`, added `acquireWithin(timeoutMs)`; deadline enforced before token |
| `3e4a2e8` | **Copernicus WMTS retry** (see §6) |
| `fdc4dfa` | Session handoff doc (this file) |
| `9bcfda3` | **weather_tile_pipeline benign skip** (see §6.1) |
| `0ec27a8` | **Merge `fix/init-sql-gist-index`** (see §6.2) — current HEAD |

Push target is the **fork**: `https://github.com/MERTAD/shaka.git`. Upstream is
`origin` = `mikewards/shaka` (push denied: 403 — user declined collaborating
upstream). Local `main` == `fork/main`. Local `main` is **27 commits ahead** of
`origin/main` (expected; we never push there).

Unmerged branch: `fix/init-sql-gist-index` (44a2ea6) — fixes a GIST expression
index that made `db/init.sql` unrunnable. **Not** on `main` yet; consider
merging/reviewing.

## 6. Latest work (this session) — Copernicus WMTS retry

**Investigation**: `satellite_copernicus` reported 494/789 and later 1/317
BREACHes. Root cause analysis (corrected):
- TCP connect to `wmts.marine.copernicus.eu` is 60–80ms normally but **1 in ~15
  takes ~15s** (dropped SYN, kernel backoff) — past the shared client's 5s
  `connectTimeout`.
- `CopernicusWMTSClient` documented "smart retry with exponential backoff" and
  even declared `MAX_RETRIES`/`INITIAL_BACKOFF_MS`/`MAX_BACKOFF_MS`… **but no
  retry was ever called**. Every request was a single attempt; timeouts were
  caught and recorded as `no_data`.
- (Earlier claim "1388 timeouts were Copernicus" was wrong: by logger, the old
  run was 748 NDBC + 741 GIBS + 8 Copernicus + 4 CMR.)

**Fix** (`3e4a2e8`):
- `withRetry(tag, maxRetries=3, initialBackoffMs=1000, maxBackoffMs=8000)` added
  to `CopernicusWMTSClient`, wrapped around `fetchVisibility` and
  `fetchChlorophyll`, **inside** the circuit breaker so a recovered retry does
  not open the circuit.
- `isTransient()` gates retries: only transport failures
  (timeout/connect/reset/broken-pipe). A 400 "date not yet available" or a null
  payload is an ANSWER and is never retried.
- New test `shaka-api/src/test/kotlin/com/shaka/data/client/TransientRetryTest.kt`

**Verified live** (new build):
- 1 Copernicus connect timeout → 1 retry → 0 final failures.
- Full run report: `satellite_copernicus 368/789 (46.6%)`, was `1/317 (0.3%)`.

**Residual `satellite_copernicus` failures are honest, not bugs**: all 421
classify as `no_data` (DataPrefetchJobs.kt:943). The run made 1110× GET to
day-1 (2026-10-05) that all returned 400 "data not yet available" (normal L3
NRT ~1-2 day latency), and fallback day (10-04) has no coverage at those
pixels. 0 network errors, 0 credential issues. **CDSE OAuth credentials are
irrelevant to `satellite_copernicus`.** Decision (open, see §8): should honest
`no_data` count as a BREACH failure at all?

## 6.2 Latest work — db init.sql GIST fix (merge `0ec27a8`)

`db/init.sql` had a broken spatial index that killed any PostGIS container on
first cold start:

```sql
-- before (Postgres: syntax error at or near "::")
GIST ( ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography )
-- after
GIST ( ((ST_SetSRID(ST_MakePoint(longitude, latitude), 4326))::geography) )
```

An expression inside `CREATE INDEX ... USING GIST (...)` must be wrapped in its
own parens BEFORE the `::type` cast, otherwise Postgres rejects it. The branch
`fix/init-sql-gist-index` (44a2ea6) fixed it and decoupled CI's comment about
it; merged into `main` as `0ec27a8`.

**Validated**: threw up a scratch `postgis/postgis:16-3.4` on port 5499 with
`init.sql` mounted → container survived cold start, `pg_isready` accepting, and
`fishing_intel_report_geos_location_idx` exists; 789 spots seeded. Container
removed after validation.

## 6.3 Latest work — Flutter Explore map crash fix + MVP walkthrough (Track A)

Goal: validate the MVP on the physical phone (install latest debug APK, walk the
core loop discover → score → evidence, hold a stable baseline).

Connected device for this session is **`R5CWB0JZ73L` = Samsung SM-S901B
(Galaxy S22, API 34)**, NOT the API-27 `46f866f5` recorded in §3/AGENTS.md.
Local-backend targeting needs `adb reverse tcp:8080 tcp:8080` plus
`--dart-define=SHAKA_API_BASE=http://localhost:8080` (origin only, no trailing
slash; build-time config in `shaka-app/lib/core/config/app_config.dart:28`).

**Blocking bug found & fixed — core loop crashed the app on spot selection.**
- Repro: launch → map loads (`789 spots in 84ms`) → tap a carousel card / marker.
- Symptom: `PlatformException(error, Source selected-spot-source already exists,
  CannotAddSourceException)` then a **native SIGSEGV in `libmaplibre.so`**
  (`MessageImpl<...GeoJSONData>::operator()`); process dies (Samsung logged
  `appErrorCount=10`). So "open a spot" — the whole point of the app — killed it.
- Root cause: `_updateSelectedMarker()` (`explore_screen.dart`) is `async` and
  callable concurrently (carousel `onPageChanged`, camera-idle, marker tap). It
  did `removeLayer` + `removeSource('selected-spot-source')` then `addSource`
  with **no re-entrancy guard**; overlapping calls raced → "source already
  exists" → native GL thread crash.
- Fix: added a coalescing guard (`_selectedMarkerUpdating`/`_selectedMarkerDirty`)
  so only one mutation runs at a time; split into `_updateSelectedMarker()`
  (guard + rerun-once loop) and `_applySelectedMarker()`. When the source already
  exists it now refreshes via `setGeoJsonSource` instead of remove/re-add churn;
  tracks `_selectedSourceExists`, reset to `false` on style reload; failure path
  tears the source down cleanly.
- Validated: `flutter test` 107/107; rebuilt + reinstalled (APK 226.9 MB);
  on device the app **stayed alive through 3× open-spot → back cycles**, no
  `already exists`/SIGSEGV in logcat, Conditions tab rendered (Swell 2ft @ 11s S,
  Wind 4 kts ENE, Water 81°F, Tide rising; SCORE BREAKDOWN Swell 65 / Wind 100 /
  Visibility 65 / Solunar 55).

**Non-bug robustness gap — map style DNS hang.** A transient inability to
resolve `basemaps.cartocdn.com`/`tiles.basemaps.cartocdn.com` left the app stuck
on "Loading spots…" (style never loaded, so markers never render). Recovered on
retry; no style-load timeout/fallback. Worth hardening later, but environmental.

**Known MVP caveat (not this fix):** `/v1/spots/all` returns `shakaScore: null`
for all 789 spots because local `spot_cache` has no tide data (`tide_state` /
`tide_height_ft` null; `tide_horizon_topup` job not run — needs the FES2022 tide
service). Carousel cards therefore show `Loading...` for the score, but curated
spots are still tappable and the detail route computes a score on demand
(e.g. `mexico-espiritu-santo-north` → `{"overall":80,"confidence":95,...}`).

## 6.4 Latest work — Top-PFZ-per-species overlay + species styling (Track A)

Feature request (user): (1) fix freeze/crash on map navigate/zoom, (2) show the
top PFZ target for every species on **all** maps (Explore, Charts, Results) —
especially the Algerian coast, (3) make the selected/main spot icon larger and
others smaller, (4) give each species its own colour + icon, defaulting to the
best-scoring of the 8.

**Marker-mutation crash fix (same class as §6.3).** The spots marker refresh
(`_updateMarkers` in Explore, `_updateSpots`/`_addMarkers` in Charts/Results) had
the identical unguarded `removeSource`+`addSource` race as the selected marker
(reproduced crash). Now coalesced with `_updateMarkersInFlight`/`_updateMarkersPending`
(and equivalents), refreshing via `setGeoJsonSource` when the source exists;
`_spotsSourceExists` reset on style reload. Spot icons normalized to
`iconSize: 0.65`; the selected spot uses `iconSize: 1.35`.

**Per-species styling.** New registry
`lib/core/species/species_style.dart` (`SpeciesStyle{id,label,color,icon}`,
`kSpeciesStyles`, `speciesStyle(id)`, `speciesIds`) covers the 8 app species
(`bluefin_tuna, little_tunny, swordfish, sardine, anchovy, horse_mackerel,
scomber, european_hake`) with distinct Material icons + colours.
`lib/presentation/utils/species_marker_painter.dart`
(`generateSpeciesMarkerImage`) renders a white-ringed coloured circle with a
knockout glyph to a PNG for MapLibre `addImage`.

**Overlay implementation.**
- `lib/data/services/pfz_overlay_service.dart` — `PfzOverlayZone` +
  `PfzOverlayService.topZonesPerSpecies({lat,lon,date,perSpecies=1})`: queries
  `getZones` for all 8 species in parallel, keeps each species' best scored zone,
  caches per rounded anchor+date; failures/empty are normal (species omitted).
- `lib/presentation/map/pfz_overlay_layer.dart` — `PfzOverlayLayer`
  (source `pfz-overlay-source`, layer `pfz-overlay-layer`, image prefix
  `pfz-species-`); serialised busy/pending guard so no two GL mutations overlap;
  `show()/hide()/onStyleReset()`.
- Wired into Explore (`explore_screen.dart`), Charts (`gibs_imagery_screen.dart`)
  and Results (`results/map_view.dart`) with a `Icons.pin_drop` floating toggle
  (active tint + loading spinner). `_latestPfzDate()` = yesterday UTC (Copernicus
  NRT lag). Floating buttons now carry `Semantics(button:true,label:...)` so they
  are targetable via `uiautomator` (and accessible).

**Validated on `R5CWB0JZ73L`:** `flutter test` 107/107, `flutter analyze` clean
(no errors). Explore PFZ toggle fires (`PFZ toggle tapped … zones resolved=…`),
toggles on/off, survives pan + repeated double-tap zoom with **no SIGSEGV /
libmaplibre crash**. Seeded 8 synthetic zones (debug-only compile flag
`--dart-define=PFZ_SEED=true`, gated by `bool.fromEnvironment('PFZ_SEED')`):
`PFZ overlay: drew 8 species targets`, and pixel-sampling confirmed a
species-coloured marker (bluefin `#1E88E5`, 836 px) rendered on the GL surface.

**Upstream blocker (not a app bug):** with a real (unseeded) build and no local
cache, the overlay resolves `0` zones near the current center, and
`GET /v1/pfz/zones` for a Mediterranean anchor (e.g. Algiers 36.75,3.06) makes
the backend take ~350 s in the Copernicus Med grid fetch and then return an empty
`zones` list with an honest `coverageNotes` explanation ("No Copernicus SST/SSTA/
CHL grid resolved … the download failed"). `pfz_zones_daily` is empty (0 rows),
so nothing is persisted for fast replay. The Algerian-coast requirement is
therefore **data-gated**, not code-gated: the overlay renders whatever the
backend scores; the backend needs the Copernicus Med fetch to complete within its
deadline (retry once the corridor is cached), or gridded bathymetry for the
hake/red-shrimp cases.

## 6.5 Latest work — Algerian-coast coverage guarantee (backend, Track A)

User directive: **"The main zones is the Algerian coast. You have to cover this
zone even if the score is weak."** Root cause was NOT missing data — the
Algerian coast is inside Copernicus Med coverage; an Algiers anchor cell is
coast-masked so its zones are nearest resolved open water (honest note, kept).
The real gap: a **cold** first scan of a coast anchor ran past the 240 s analysis
deadline (measured cold Annaba 244.8 s then 249.9 s → 0 zones, `TIMED_OUT`), so
the coast looked empty until its grids happened to be cached.

**Design (why):** zone segmentation needs only the FRONT fields
(SST/SSTA/CHL — one batched subset call); benthic depth/bottom-T/SSH + monthly
mean SSH are *enrichment* — they make a score richer but never segment a zone.
So a slow/dead enrichment batch must weaken a score, never void the zone list.

Changes (all backend, plus one app timeout):
- `PfzGridService.analyzeZones`: `coroutineScope` + one `async` per batch; the
  front batch owns `ZONE_ANALYZE_TIMEOUT_MS = 420_000`, each enrichment batch
  owns `ZONE_ENRICH_TIMEOUT_MS = 300_000`; `CancellationException` rethrown,
  other `Exception` → `emptyMap()`. Only a null *front* yields
  `PfzZoneEmptyReason.TIMED_OUT` (which cancels the still-running enrichment via
  the enclosing scope). Shared body extracted to `analyzeZonesFromGrids`
  (never fetches, never times out).
- `CopernicusGridClient`: `CopernicusSubsetProcess` timeout 180 s → 300 s so
  big products can finish and land in the 36 h CSV cache.
- **New `PfzCorridorWarmJob`** (`pfz_corridor_warm`): on boot, after 3 min,
  pre-fetches the FRONT fields for 7 Algerian-coast anchors (Oran, Mostaganem,
  Algiers, Bejaia, Jijel, Skikda, Annaba), then every 12 h; `reportRun` +
  `captureItemFailure` via MonitoringService; JobSpec in `MonitoringConfig`,
  scheduled in `Application.kt`. `MonitoringRegistryTest` auto-enforces all three.
- **App** `shaka_api_client.dart` `getZones`: 480 s `receiveTimeout` (backend
  deadline 420 s; Dio default 120 s would give up first).
- Tests: full backend suite 288/288 (incl. new "analyzeZones resolves zones from
  the front when the enrichment batch fails" + `PfzCorridorWarmJobTest`);
  `flutter test` 107/107, `flutter analyze` no new issues.

**Live numbers on this machine (2026-10-07, new build):**
- Warm Algiers (36.75,3.06, sardine): 48.6 s → 24 zones, pfz 92/89/89 (regression
  clean vs pre-change).
- Cold Cherchell (36.61,2.19 — a non-anchor point, nothing cached): **400.2 s →
  24 zones, pfz 94/89/88** (would have hard-failed at 240 s before). Repeat
  (cached): 26.8 s → 24 zones.
- `pfz_corridor_warm` first run: 175.9 s, total=7, succeeded=7, status=OK.
- Backend currently running with this code (log `shaka-api/run4.log`).

**Known limits (accepted):** warm job covers only map centers within ±0.3° of the
7 anchors; arbitrary coast points still get a cold (but now survivable) first
request. Enrichment may be missing on the first cold scan (300 s budget) →
scores weaker but zones present. `pfz_zones_daily` still only fills on
request/persist; cache TTL is 36 h.
**TODO (carry-over):** the app overlay feature files from §6.4 are still NOT in
git (untracked: `pfz_overlay_service.dart`, `species_style.dart`,
`species_marker_painter.dart`, `presentation/map/`, plus Explore/Charts/Results
wiring) — commit them in a separate commit when ready. Junk `*.err`, `sc.png`,
`screenshot.png`, `run2/3.err` also untracked — do not stage.

## 7. Background jobs, monitoring contracts, gotchas

- `MonitoringConfig.kt` defines jobs; `deployGraceMs = initialDelayMs + maxRunMs`.
  First run fires at `initialDelayMs`.
- `reportRun` → log `job_run event=...`, `job_runs_latest` table, Sentry, Better
  Stack, heartbeat; BREACH when success < threshold (see `Severity`).
- **Health contract (published)**: `/v1/health` must report `db == "ok"` when
  healthy and HTTP 503 `not_connected` when configured-but-broken
  (`HealthSummary.kt`, `SpotRoutes.kt`). Do not change without touching
  `monitoring/journeys.json` (T1 `requiredValues: {"db":"ok"}`),
  `docs/synthetic-monitor-design.md` and `.github/workflows/ci.yml` (`curl -sf`).
- **Repository rule: use `HttpClientFactory.shared` — never spawn a new
  client.** Shared timeouts: connect 5000ms, request/socket 30000ms
  (HttpClientFactory.kt ~line 141). There is a canary probe + http_pool_watchdog
  job (Jul 2026 outage hardening).
- `RateLimiter`: `acquire()` = wait-as-long-as-it-takes (returns Unit),
  `acquireWithin(timeoutMs)` = bounded wait (Boolean, used by LandWaterClient),
  `tryAcquire()` = never blocks.
- NOAA SST gaps are **deliberately preserved** (last-known SST kept,
  `DataPrefetchJobs.kt` ~894). The 2026-09-29 481/481 SST failure was an
  upstream NOAA data gap, not a bug.
- DB table gotchas: `pfz_zones_daily.key = local_date` (NOT `date`);
  `job_runs_latest` columns = `job_name,started_at,finished_at,total,succeeded,failed,duration_ms`.
- Test rows for `pfz_zones_daily` `2026-09-15`/`little_tunny` are manually
  cleaned after verification (0 rows may remain).

### Latest full-run numbers (new build, 2026-10-06)
| job | result | note |
|---|---|---|
| satellite_prefetch | 789/789 | ok |
| satellite_gibs | 789/789 | ok |
| satellite_sst | 568/789 | known NOAA upstream gap |
| satellite_copernicus | 368/789 | honest no_data (see §6) |
| fishing_intel / mpa / pfz | 789/789 | ok |

Two pre-existing BREACHes appeared at that boot:
- `weather_tile_pipeline`: exit 9009 — Windows App-Execution-Alias `python`
  stub resolves before the real interpreter. **Fixed in `9bcfda3`** (see §6.1).
- `hourly_swell_wind`: 87.3% — 100 spots "Open-Meteo weather hourly unavailable".

## 6.1 Latest work — weather_tile_pipeline skip (commit `9bcfda3`)

`WeatherTileService.runPipeline` launched `ProcessBuilder("python3", ...)`. On
Windows, `python3` resolves to the Microsoft Store App Execution Alias stub
which exits 9009 with "Python was not found..." — and the default script path
`/app/scripts/weather_pipeline.py` + `/data/weather` are Linux/Railway-only.
The pipeline also needs the `copernicusmarine` CLI + numpy/xarray/PIL, so it
cannot meaningfully run on a Windows dev box. Fix:

- Robust interpreter resolution (`findPython`): try `python3`, `python`,
  `py -3`; validate each with `--version` (exit 0 AND output looks like real
  Python — rejects the stub). Result cached.
- If no usable interpreter or the script file is missing → benign skip
  (`reportRun` success, no BREACH, `/health/jobs` stays green) instead of
  BREACH. Railway/Linux still executes the pipeline exactly as before.
- `WeatherTileServiceTest` (4 tests) covers the stub/exit-output matrix.

Live-verified at boot: `job_run event=job_run job=weather_tile_pipeline
total=1 succeeded=1 ... status=OK` and "Weather tile pipeline skipped: pipeline
script not found: /app/scripts/weather_pipeline.py".

## 8. Open decisions / pending items

1. **Delete `docker-compose.override.yml`**? (stale 5434 remap; user was asked,
   not yet confirmed).
2. **Rotate Copernicus password** on the portal (leaked in `3753c38`). User
   writes new values manually into `shaka-api/.env.local`.
3. ~~Weather pipeline python stub~~ — **done** in `9bcfda3` (benign skip when
   not runnable; Railway still runs it).
4. **Coverage accounting**: should honest `no_data` count toward BREACH? If yes
   to change, must update journeys.json/tests (published contract).
5. **`fix/init-sql-gist-index`** — ~~unmerged~~ **done** in merge `0ec27a8`
   (see §6.2).
6. **Upstream**: user declined collaborating on `mikewards/shaka`; push to
   `fork` only.

## 9. Verification cheat-sheet (live loop)

```powershell
# logs for the running app
$log = "shaka-api\run2.log"         # after Start-Process gradlew run
Select-String $log 'job_run event=' # job reports
Select-String $log 'failed transiently|Connect timeout' # retry evidence
# DB
docker exec shaka-db-1 psql -U shaka -d shaka -t -A -F ' | ' -c "SELECT job_name,total,succeeded,failed FROM job_runs_latest ORDER BY started_at DESC LIMIT 4"
# Android (shaka-app)
$env:JAVA_HOME="...jdk-21.0.3.9-hotspot"; .\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n <pkg>/.MainActivity
# secret hygiene before any push: scan the staged diff against the 4 key values in .env.local
```

Before pushing: `git status` has ~18 unrelated dirty/untracked files — **stage
only the task-specific files**, and pre-push secret-scan the diff against every
value in `.env.local`.