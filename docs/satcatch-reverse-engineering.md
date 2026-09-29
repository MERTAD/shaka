# SatCatch — Reverse-Engineering Notes (layers, species, sources, APIs, PFZ score)

**Status:** Reference / competitive analysis. No SatCatch code or assets are used in Shaka; this document records what is publicly observable from their shipped web/mobile bundle so the Shaka PFZ engine can be aligned with the real competitive surface.
**Date:** Sept 2026
**Method:** Static inspection of the public web app bundle (`app.satcatch.com`), which is the same SPA the Android app renders in a WebView. No interception, no credentialed access, no private endpoints were touched.

## TL;DR / What this is for

SatCatch is a paid daily "Potential Fishing Zone" (PFZ) product. This doc captures:
- The **38-layer** environmental map catalog and where each layer's data comes from.
- The **species catalog** (codes, names, region groupings, depth/lat envelopes).
- The **backend/API surface** (Supabase, edge functions, signed daily tiles).
- The **observable PFZ scoring pipeline** (per-species cell score → clustered polygon zone → confidence-by-source-consensus → daily validity → day-over-day delta).

The actual **ML model weights / scoring function are server-side and not recoverable** from the bundle. What is recoverable — and what Shaka mirrors — is the full observable contract around them.

---

## 1. Layer catalog (38 layers)

Extracted from the `LabsLayersSheet` chunk. Group keys are the on-map "Layers" sheet groups. `premiumLocked` = requires a paid tier; submodes are selectable variants with their own source/resolution.

### Fishing
| Layer id | What | Source | Lock |
|---|---|---|---|
| `viirs_boats` | VIIRS night-time boat detections (BETA) | NOAA VIIRS | premium |
| `sst_chl_fronts` | SST / chlorophyll fronts | derived | premium |
| `fsle` | Finite-Size Lyapunov Exponents (convergence lines) | altimetry-derived | premium |
| `thermocline` | Thermocline depth | ocean model | premium |

### Temperature
| Layer id | Submodes (source) | Lock |
|---|---|---|
| `sst` | `mur_sst` = NASA JPL **MUR 1 km, Global** (free) / `high_res_sst` = **Med 1/100°** HD (premium) | free + premium |
| `temp_depth` | temperature-at-depth (model) | premium |
| `sst_anomaly` | SST anomaly (model) | premium |
| `bottom_temperature` | bottom temperature (model) | premium |

### Water
| Layer id | Submodes (source) | Lock |
|---|---|---|
| `chlorophyll` | `chlorophyll` = **CMEMS** standard / `high_res_chl` = **Med 1 km** HD / `pace_chl` = **NASA PACE 1 km daily** | premium |
| `salinity` | model | free |
| `nitrate` | model | free |
| `mld` | mixed layer depth (model) | premium |
| `zooplankton` | model (biogeochemical) | premium |
| `primary_production` | model | premium |

### Movement
| Layer id | Submodes | Lock |
|---|---|---|
| `ocean_currents` | surface currents (particle/vector) | free |
| `vertical_current` | model | premium |
| `stokes_drift` | surface drift (Stokes drift) | free |
| `waves` | `wave_particles` = animated direction ("now") / `wave_height` = height, dated ±7 d | free |
| `altimetry` | absolute dynamic topography (ADT) | premium |
| `ssh_altimetry` | sea surface height | premium |

### Seabed
| Layer id | Source | Lock |
|---|---|---|
| `bathy_vision` / `med_sea_bed` | **EMODnet** depth grid (shipped client-side as `algerian_depth_grid.bin`) | premium |
| `isobaths` / `bathymetry_grid` | EMODnet | free |
| `seabed_live` | user personal depth mapping — "coming soon" | free |

### Surface
| Layer id | Source | Lock |
|---|---|---|
| `modis_truecolor` | **NASA MODIS** true color | free |
| `live_clouds` | live satellite cloud cover | free |
| `weather` | **open-meteo** | free |

### Traffic
| Layer id | Source | Lock |
|---|---|---|
| `ais_ship_traffic` | AIS vessel tracking | premium |

> Source summary: **CMEMS** (Copernicus Marine), **NASA JPL MUR / PACE / MODIS**, **NOAA VIIRS**, **EMODnet** bathymetry, **open-meteo** weather, **Mapbox GL** basemap. Not independently licensed from the bundle; each is a public/standard ocean-data source of the kind Shaka already uses.

---

## 2. Species catalog

Three groupings are present in the code: a FR display map (`de`), a full i18n species map (`tt`) used by the app, and a West-Africa effort-atlas (`Me`) carrying per-species depth/lat envelopes + provenance.

### 2a. Display / app species (i18n `tt` map), grouped
- **Pelagic / tuna:** PIL Sardine, ENGRENC Anchovy, SAA Sardinella, HOM Horse mackerel, VMA Chub mackerel, BON Bonito, BOG Bogue, ALB Albacore, WHG Whiting, SWO Swordfish, LTA Little tunny, MTU/TUN Tuna, **BFT Bluefin Tuna** (special-cased "indicative zone, field validation in progress" card), PEL Pelagic.
- **Demersal:** HKE European hake, MUT/MUR Red & striped red mullet, PAE Common pandora, DEC Common dentex, SBG White seabream, SBA Gilthead bream, EBA European seabass, MEO Grouper, SOL Common sole, MUL Grey mullet, RSC Red scorpionfish, CON European conger, BRB Black seabream.
- **Cephalopods / crustaceans:** OCT Common octopus, CTL Common cuttlefish, ARA Red (royal) shrimp, PAR Deep-water rose shrimp, PEZ Deep-sea red shrimp.
- **Indian Ocean set (+ restricted variants):** YFT Yellowfin tuna, BET Bigeye tuna, RAG Indian mackerel, OIL Oil sardine, SQZ Indian squid, TRE Trevally, POM Silver pomfret, NEM Threadfin bream, COM Seerfish, LVH Trash fish, CAT Marine catfish, plus `*_Restricted` codes (RYF, RSK, RPZ, RCT, RCO, RTR, RPO, RCA, RNE, RSQ, RLV, RBE, RLO).
- **Saudi Red Sea set (region `SA_RS`):** COM Kingfish, TRE Jacks, BSX Groupers, EMP Emperors, SNX Snappers + Indian-set codes.

Species vocabularies: `ASFIS` codes are prefixed `"ASFIS:<CODE>"`; zones carry `species_code` / `species_csv` / `species_codes` and a `species_vocab` of `"ASFIS"` or the app's own list.

### 2b. West-Africa effort-atlas (`Me`) — per-species depth/lat envelope + provenance
This is the demersal depth-band source. `provenance`: `mixed` (distribution+effort), `grey` (literature/grey), `peer` (peer-reviewed).

| Code | Species (scientific) | Depth (m) | Lat (°N) | Provenance |
|---|---|---|---|---|
| OCC | Octopus vulgaris | 20–100 | 21.3–28 | mixed |
| CTC | Sepia officinalis/hierredda | 20–150 | 21.3–26.4 | grey |
| SQR | Loligo vulgaris | 50–75 | 21.3–22 | grey |
| HKE | Merluccius merluccius (European hake) | 100–1000 | 28.7–35.8 | mixed |
| HKM | M. senegalensis | 200–600 | 21.3–33 | peer |
| HKB | M. polli (Benguela hake) | 300–1000 | 21.3–28.3 | peer |
| DPS | Parapenaeus longirostris (deep-water rose shrimp) | 75–700 | 29.4–35.8 | mixed |
| ARI | Aristeus / Aristaeomorpha / Plesiopenaeus (red shrimps) | 300–1000 | 29.4–35.8 | grey |
| SBA | Pagellus acarne (axillary seabream) | 20–100 | 21.3–35.8 | grey |
| PAC | Pagellus erythrinus (common pandora) | 30–100 | 21.7–26.1 | grey |
| PAR | P. bellottii (red pandora) | 20–100 | 21.3–26.1 | grey |
| DEN | Dentex canariensis | 10–100 | 21.3–26.1 | grey |
| DEP | D. gibbosus (pink dentex) | 30–150 | 21.3–26.1 | grey |
| DEL | D. macrophthalmus | 200–400 | 21.3–28 | grey |
| DEM | D. maroccanus | 30–200 | 21.3–28 | grey |
| RPG | Pagrus pagrus (red porgy) | 50–100 | 21.6–28 | grey |
| GBR | Plectorhinchus mediterraneus (rubberlip grunt) | 10–150 | 21.3–26.1 | grey |
| BRB | Spondyliosoma cantharus (black seabream) | 20–100 | 21.3–28 | grey |

A real ingest sample also confirms **bonito (Sarda sarda), anchovy (Engraulis encrasicolus), sardine (Sardina pilchardus), round sardinella (Sardinella aurita), chub mackerel (Scomber colias), swordfish (Xiphias gladius)** as active pelagic species.

---

## 3. Backend / API surface

- **Platform:** Supabase project `vofmknvyodjlhujyrbwg.supabase.co` (PostgREST + Auth). Tables `profiles`, `user_entitlements`, `subscriptions` exist but are RLS-locked (401 without a session). Client reads `get_user_tier` RPC.
- **Edge functions:** `/functions/v1/notify-login`, `/functions/v1/get-tile-token`. The latter returns signed tokens (`md5`, `expires`, `kid`) for two tile buckets: `["ocean", "pfz"]`, ~10-min expiry.
- **Tile API:** `https://tiles.satcatch.com/{layer}?v={version}&md5=…&expires=…&kid=…`. Data is **pre-rendered and versioned per day** (observed build tag `20260928s4`). Zones and ocean layers are delivered as signed tiles, not open REST rows.
- **Other services:** Mapbox GL (basemap/tokens), open-meteo (weather + cloud tiles), `ipapi.co/json/` (IP geolocation), EMODnet depth grid (client-side binary).
- **App architecture:** the Android app is the same SPA in a full-screen WebView; there is no separate native zone API surface reachable without a login.

---

## 4. PFZ score — the observable algorithm

SatCatch does not compute PFZ on-device. The bundle reveals the **inputs, intermediate artifacts, and per-species outputs** of a server-side ML pipeline.

### 4a. Zone data model (real ingest sample, valid 2026-01-15)
Each zone is a polygon feature whose properties are:

```
zone_id        e.g. "SARD-W-197"  (SPECIES-GROUP-nnn; W/E/C = West/East/Central basin)
sub_zone       W | E | C
species        scientific name
species_common common name
mean_score     per-zone model score, normalized 0..1
max_score      per-zone peak cell score, 0..1
area_km2       polygon area
center_lon/center_lat  centroid
coast_distance_km      distance to shore
valid_from / valid_to daily validity window (ISO Z)
source_file    e.g. "map (3).geojson"  (zones ingested as pre-made map exports)
source_tag     "A" | "B"  (which independent source produced the zone)
match_group    consensus group id (e.g. "VHC_001")
overlap_or_near_5km  boolean
```

### 4b. Per-species score = the model cell score
The displayed card score (`scorePct`, 0–100 %) is the model output, meaned/maxed per zone. Three model sources are tagged:

| `source` tag | i18n tag | Model |
|---|---|---|
| `ml_pelagic_g1` | `pfzSourceTagV52` | ML **v52** of their pelagic model (ocean color + thermal fronts, + FSLE/altimetry) |
| `anomaly` | `pfzSourceTagAn` | SST-anomaly run |
| `demersal_effort_atlas` | — | demersal model driven by the **actual distribution of the species in the water column** (perfishing), clipped to each species' depth + lat envelope + provenance (table 2b) |

Zone assembly: cluster above-threshold cells → polygon → `mean_score`/`max_score` of its cells.

### 4c. Confidence = geometric consensus between independent sources
- Zones come from two independent source files, tagged `source_tag: "A"` and `"B"`.
- When their polygons **overlap or lie within `near_radius_m: 5000` (5 km)**, they are merged into a `match_group` (`VHC_*`) and flagged `sources: ["A","B"]`, `pfz_type: "Very High Confidence PFZ"`, `confidence: "Very High"`.
- Displayed confidence enum = **high (3) / medium (2) / low (1)**; the card bar color bands by score (`≥85 / ≥70 / ≥55` → 4 blue shades).

### 4d. Card contents (what a zone shows)
Species, `Score: N%` bar, confidence high/med/low, area km², seabed depth (≈grid vs ≈100 m), validity date, port, **VIIRS fishing-history hours** (`label_support`), day-over-day delta (`move_state` / `score_delta` / `trend` → new / moved / stronger / weaker), source tag, and a GPX export button.

### 4e. Derived layers on top of the score
- **Catchability windows:** hourly `{hour_ist, score, solar_elev, moon_illum}` + `best_window` (squid/cuttlefish night fishery).
- **Zone drift:** zones advected by current; `track[]` with `drift_km`, `cur_kn`, `cur_dir`, plus `halted` flags.
- **At-sea depth anchoring:** the app re-derives seabed depth at the vessel position from the EMODnet grid.

### 4f. What is NOT recoverable
The ML weights, the feature fusing, and the exact cell→`mean_score` function are server-side. The feature *domains* are observable (thermal fronts, chlorophyll fronts, FSLE, MLD, thermocline, altimetry, VIIRS history, catchability windows) but not their coefficients.

---

## 5. Mapping to Shaka (how this informs the honest engine)

Shaka deliberately does not claim ML it cannot run. It publishes per-factor scores with confidence tiers and honest gaps. The competitive surface SatCatch exposes suggests these concrete Shaka features:

1. **Confidence bands on the score** (mirror their `high/med/low` + `≥85/70/55` color bands) so a strong zone *looks* strong and a weak one looks weak — already partly present in Shaka's per-species result.
2. **Per-species depth + lat envelope surfaced on the card**, sourced with a provenance tag (`cited` / `expert` / `mixed`) so the band is visibly an estimate where it is one. This is the `Me`-atlas idea and maps to Shaka's existing `unscorableRequirements` / factor `conf` tiering.
3. **A `sourceTag` on each zone** (`ml_v52` / `anomaly` / `demersal_atlas`) so a caller can tell which pipeline produced a number.
4. **Day-over-day zone delta** (`new / moved / stronger / weaker` with `score_delta`) — SatCatch clearly stores yesterday's zones to diff them.
5. **Optional layer parity** for Shaka's real Copernicus sources: `sst_anomaly` (Shaka has SSTA), chlorophyll fronts, MLD, thermocline, FSLE.

### Water-column vs bathymetry semantics (implemented)
A key correctness rule surfaced by this analysis and already fixed in Shaka: a pelagic's "0–200 m" depth band is the **water column** (top layer), **not** a bathymetric gate. Bluefin/little tunny/swordfish live in the top layer and roam basins with >2000 m bottom depth. Shaka models this explicitly:

- `DepthScope.WATER_COLUMN` (PfzModels.kt) — the band is a below-surface band; bathymetry must not gate or score it.
- `bluefin_tuna`, `little_tunny`, `swordfish` declare `water_column` (species_profiles.json).
- `PfzFactors.depth()` returns `Missing` for those species, so the depth weight renormalizes onto the real drivers (SST, gradients, anomaly, CHL) and deep-basin zones are `SCOREABLE`, not `unavailable` (PfzFactors.kt).
- Substrate-bound species (demersals, cephalopods, crustaceans, inshore, and the shelf-gated small pelagics) remain `BATHYMETRY` and still gate hard.
- `PfzSpeciesRegistry` rejects `water_column` on non-pelagic guilds (load-time guard).
- Regression tests in PfzEngineTest cover: deep 1500 m basin (pelagics scoreable / demersals gated / scope declarations correct).

---

## 6. Provenance / limitations
- All findings are from the **public** web bundle. No private endpoint, credential, or interception was used.
- The scoring **formula** and ML **weights** are server-side and not in the bundle; only the observable contract is documented.
- Data-source attributions (CMEMS, MUR, PACE, MODIS, VIIRS, EMODnet, open-meteo) come from the layer labels/submode notes in the bundle, not from independent verification of SatCatch's upstream ingestion.
- This document is for competitive/feature analysis and does not copy SatCatch code, data, or assets into Shaka.
