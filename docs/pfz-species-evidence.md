# PFZ species evidence matrix

**Status:** Working reference. Every numeric band in `shaka-api/src/main/resources/pfz/species_profiles.json` must trace to a row here, and every row here states whether the variable is actually *operational* (Shaka has a real data source for it) or `unscorable-no-source`.

**Why this file exists:** PFZ v1 declared one flat weight map per species, so a hake and a sardine were "different species" only in their weights. They are not different in the same way. A hake's controlling variables are *benthic* (bottom temperature, bottom current, shelf-break depth) and are physically absent from the data the app was fetching, which was all surface. The score was therefore a statement about sea-surface temperature with a Mediterranean name on it. The same is true of bluefin (feeding vs spawning parameterisations are different models) and sardine (regional thermal optima differ by ~5 °C).

**Rules this matrix enforces:**
1. A band is only usable if its variable is `operational`. Otherwise it goes in `unscorableRequirements` and the species says so out loud.
2. `required` factors gate the score. If a required factor is missing, the species returns `insufficient_data` with a null score — not a renormalized partial score. A hake without bottom temperature is not a hake assessment.
3. Anything not traceable to a row here stays `expert` tier and is listed in `lowConfidenceFactors`.
4. Never substitute a correlated variable for a documented one. Sardine does not get scored on SST because we cannot measure salinity — it gets `unscorableRequirements: ["bottom-layer salinity"]` and a lower confidence.

**Confidence tiers used below**
- `cited` — value is quoted from a primary source, with the table/section noted.
- `derived` — computed by Shaka from a cited method (e.g. an SSH anomaly from two Copernicus fields). Real data, our arithmetic.
- `expert` — plausible band, no Mediterranean citation. Always surfaced as low-confidence.

---

## Data source capability (verified live 2026-09-29)

All rows below assume these fields. Verified with `copernicusmarine describe` and a real `subset` over 2.0–2.4 °E / 41.0–41.3 °N on the NRT day D-2.

| Variable | Dataset id | Copernicus variable | Dimensionality | Notes |
|---|---|---|---|---|
| Bottom potential temperature | `cmems_mod_med_phy-tem_anfc_4.2km_P1D-m` | `bottomT` | **2D** | Already at the sea floor. No depth selection needed. |
| Sea surface height | `cmems_mod_med_phy-ssh_anfc_4.2km_P1D-m` | `zos` | **2D** | Daily mean. |
| Sea surface height (climatology) | `cmems_mod_med_phy-ssh_anfc_4.2km_P1M-m` | `zos` | **2D** | Monthly mean, same product. |
| Mixed layer thickness | `cmems_mod_med_phy-mld_anfc_4.2km_P1D-m` | `mlotst` | **2D** | σ-θ definition (de Boyer Montégut 2004). |
| Salinity | `cmems_mod_med_phy-sal_anfc_4.2km_P1D-m` | `so` | 3D, 141 levels | Bottom value = deepest non-masked level. |
| Currents | `cmems_mod_med_phy-cur_anfc_4.2km_P1D-m` | `uo`, `vo` | 3D, 141 levels | Both components in one subset call. |
| Model bathymetry | `cmems_mod_med_phy_anfc_4.2km_static` | `deptho`, `mask` | 2D static | |
| SST / SST anomaly | `SST_MED_SST_L4_NRT_OBSERVATIONS_010_004_c/d` | `analysed_sst`, `sst_anomaly` | 2D | Existing. |
| Chlorophyll-a | `cmems_obs-oc_med_bgc-plankton_nrt_l4-gapfree-multi-1km_P1D` | `CHL` | 2D | Existing. |

Common bbox: `-17.2917 … 36.2917 °E`, `30.1875 … 45.9792 °N`.

**`ssh_anomaly` is derived, not published.** The product's static dataset contains `deptho`, `deptho_lev` and `mask` only — there is no `mdt` field, so absolute dynamic topography is not available from this source. PFZ computes `SSHa = zos_daily − zos_monthly_mean` from the two datasets above. Both are real observations from the same model; the differencing is ours, so the factor is `derived` tier and a monthly-mean fetch is cached per box.

**Permanently unscorable** (no data source exists in this build, no proxy is permitted):
- Substrate type (hard / soft / rubble) — required by octopus spawning, sole, seabream.
- Dissolved oxygen — required by octopus, benthic scavengers.
- PAR / euphotic depth — required by small pelagics for the photic requirement.
- Zooplankton / primary production.
- Coast distance (needs a coastline dataset; GEBCO depth gives a proxy but a depth threshold is not a distance).

### Measured against the live store, not just the catalogue

`LiveCopernicusGridTest` (opt-in, `LIVE_PFZ=1`) runs the real toolbox. Measured at 41.0 °N / 2.2 °E, NRT day 2026-09-28, 0.08° box, 16 cells:

| Field | Measured | Reading |
|---|---|---|
| `bottomT` | 13.00 – 13.42 °C | Confirms the units are °C, not Kelvin. Also lands inside Colloca et al.'s hake SBT band (11.8–15.0 °C, optimum 13.8 ± 1 °C) — the band is a real Mediterranean quantity, not a guess. |
| `so` (deepest valid level) | 38.508 – 38.599 psu, level 813 – 1397 m | Confirms psu, not the catalogue's `0.001` units string. Sits just at/above Maiorano et al.'s red shrimp upper edge (38.5 psu), at 900 m, in a water column with no red shrimp ground in it — i.e. the depth gate has to fire before the salinity gate. |
| `uo`, `vo` (deepest valid level) | speed 0.0031 – 0.0109 m/s | Well inside Colloca et al.'s < 0.034 m/s condition, at 800–1400 m. Again: the current gate alone would pass a spot that is 1 100 m too deep for hake. Gates must be conjunctive or they mislead. |
| `mlotst` | 13.6 – 29.7 m | Plausible early-September stratification onset. |
| Deepest resolved level | 813 – 1397 m | The 3D reader is genuinely reading a deep level, not a surface row. |

**Batching is real:** `uo` + `vo` came back from **one** `subset` call (asserted by counting subprocess runs), not two.

#### SSHa has mesoscale signal but not spot-scale signal

Derived anomaly, same product, two scales on the same day:

| Box | Extent | Derived SSHa range | sd |
|---|---|---|---|
| Spot | 0.08° (~9 km) | 0.060 – 0.078 m | — |
| Regional | 8° × 5° (0–8 °E, 37–42 °N), n = 23 160 | −0.127 … +0.149 m | 0.037 m |

The 18 mm spread over 9 km is not a gradient — it is one value with noise. Over the
regional box the field spans 276 mm, which is real mesoscale structure and which
does straddle Druon's ≥ −0.10 m feeding threshold.

Consequences for the design, and they differ by endpoint:

- **Spot assessment (`/v1/spots/{id}/pfz`):** use the local cell value and score it
  as an *absolute* threshold, which is exactly how Druon used it (an altimetric
  anomaly at a location, not a front). This works inside a 0.3° box and needs no
  extra request.
- **Zone analysis (`/v1/pfz/zones`):** the normal 0.3° box cannot make SSHa vary
  between zones, so it would be a constant that shifts every polygon equally and
  shapes none of them. Zone analysis therefore needs one extra **mesoscale** SSH
  pair (daily + monthly) over a wider window (≈ ±1.5°), fetched once per request
  and shared by every zone, then sampled at each zone's centroid. That is two
  additional subset calls per zone request, both cached per (window, day).

Without that second box, SSHa would be a daily-and-regional gate dressed up as a
per-zone factor. Stated here so the later implementation cannot quietly skip it.

---

## 1. Atlantic bluefin tuna — *Thunnus thynnus*

**Modes: `feeding` (default) and `spawning`. Size classes: `small` (5–25 kg), `large` (>25 kg, default).**

This is the one species in the roster where the *model* changes, not just the numbers. Druon et al. 2016 fits separate parameterisations by size class and by behaviour, and the feeding and spawning envelopes barely overlap. Defaulting to a single band set is what produced the v1 profile, which used the large-fish feeding numbers while claiming to represent "the species".

| Variable | Mode / class | Value | Tier | Source |
|---|---|---|---|---|
| SST min | feeding, large | 7.5 °C | cited | Druon 2016 §3: set from literature on SST fronts and fish dives |
| SST max | feeding, large | 24.0 °C | cited | Druon 2016 §3: intermediate between Gulf of Mexico winter 85th pct (23.83 °C, n=141) and Med summer–autumn 85th pct (24.37 °C, n=149) |
| SST min | feeding, small | 13.0 °C | cited | Druon 2016 §3: regional expertise, close to western-Mediterranean winter minimum |
| SST max | feeding, small | 26.1 °C | cited | Druon 2016 §3: 97th percentile, n=2105 |
| CHL | feeding, both | > 0.1 mg/m³, high gradCHL | cited | Druon 2016 §3 cluster analysis: feeding clusters have CHL > 0.1 and relatively high gradCHL |
| gradCHL | feeding, both | upper percentile of presence data | cited | Royer 2004 / Druon 2011 via Druon 2016 §1: gradCHL is the food-availability proxy |
| SSHa | feeding, both | ≥ −0.10 m | cited | Druon 2016 §3: 98.75th / 99.5th / 97th percentiles for small / large / undifferentiated |
| CHL | spawning | < 0.15 mg/m³, low gradCHL | cited | Druon 2016 §3: spawning clusters |
| ΔSST₃₀ | spawning | high (spring stratification build-up) | cited | Druon 2016 §1–2: monthly SST increase simulates stratification |
| SST | spawning | warm, surface | cited | Druon 2016 §1: spawning in warm, mostly oligotrophic surface waters |
| Depth | both | water column, top 50 m | cited | Walli et al. 2009 via Druon 2016 §1: 79 ± 8 % of time in the first 50 m |
| Season | feeding (W basin) | May–Sep | cited | ICCAT GBYP western Mediterranean feeding season |
| Season | spawning (E basin) | from mid-May | cited | Druon 2016 abstract |
| Season | spawning (W basin) | to mid-July, peak June | cited | Druon 2016 abstract |

**Required factors:** `feeding` → `sst`, `chla_gradient`, `ssh_anomaly`. `spawning` → `sst`, `chl`, `sst_warming`, `ssh_anomaly`.

**Why each is required.** Without gradCHL the feeding model collapses to a temperature lookup, which is not what the paper models. Without SSHa the driver is confounded with SST. A bluefin feeding score missing gradCHL is a weaker claim, so it is `insufficient_data` rather than a partial number.

**`sst_warming` is derived.** ΔSST₃₀ is the 30-day change in SST. PFZ computes it from a 30-day `SST_MED_SST_L4_NRT` series (one extra cached fetch per box per day), because the observation is a single day. `derived` tier.

---

## 2. European hake — *Merluccius merluccius*

**Modes: `nursery` (0-group recruits) and `adult`. The pilot runs a single merged band and does not split them — see note.**

This is the clearest case in the roster of a species whose controlling variables are benthic and completely absent from v1's data path.

| Variable | Value | Tier | Source |
|---|---|---|---|
| Bottom temperature | 11.8–15.0 °C, optimum 13.8 ± 1 °C | cited | Colloca et al. 2014 (ENM), 5–95 pct SBT 11.78–15.04 °C; high-recruitment cells characterised by 13.8 ± 1 °C |
| Bottom current | < 0.034 m/s (biomass > 100 kg/km²: < 0.032 m/s) | cited | Colloca et al. 2014, Table: SBC max 0.034 m/s |
| CHL | 0.10–0.91 mg/m³, median 0.26 | cited | Colloca et al. 2014, 5–95 pct for the 0–5 months before sampling |
| gradCHL | 0.00036–0.00295 mg/m³/km | cited | Colloca et al. 2014, Table: gradCHL percentile range |
| Bottom depth | 100–250 m, optimum 140–200 m | cited | Frontiers 2021 12:614675 (Iberian shelf, B-HSTHM, 2005–2016 trawl surveys) |
| Bottom depth (supporting) | 60–160 m (73 % of numbers, 81 % of biomass) | cited | Morales-Nín & Moranta 2004, W Mediterranean shelf |
| Bottom depth (supporting) | 68–168 m nursery | cited | Maynou et al. 2003 via Morales-Nín & Moranta 2004 |
| Depth range (supporting) | 28–385 m overall | cited | Colloca et al. 2014, Table: bottom depth percentiles |
| Location | outer shelf and shelf break | cited | Colloca et al. 2014 abstract: "conditions mostly occur recurrently in outer-shelf and shelf-break areas" |

**Required factors: `depth`, `bottom_temp`, `bottom_current`.**

**Why all three are required.** Colloca et al.'s habitat equation is literally
`H = Trophic(0/0.3/1) × Depth(0/1) × SBT(0/1) × SBCmax(0/1)` — a multiplicative model where any term at zero makes the cell zero. Reporting 70 because we knew depth and chlorophyll but not bottom temperature would not be a partial hake score; it would be a different model than the one the paper fitted. This is the single strongest argument for the required-factor mechanism.

**Conflict noted honestly.** Colloca et al. (Med-wide, recruits) and Frontiers 2021 (Iberian shelf) disagree on the role of SBT/SBS: Colloca finds SBT limiting, Frontiers 2021 finds bathymetry dominant and SBT/SBS the *least* relevant. Both are cited; PFZ uses the Iberian depth band (tighter, better resolved) and keeps SBT as a hard gate, because a 15 °C-bottom gate is the one thing on which they agree — Lleonart 2001 reports young hake absent from the NW Mediterranean where bottom temperature exceeds 15 °C.

**No `nursery`/`adult` split in the pilot.** The 100–250 m band is the recruit band *and* the adult shelf-break band. Colloca et al. note "recruits are found over large areas of the continental shelf and the shelf break area"; splitting them would require a fish-size input the app does not have, and a distinction without a difference is worse than an honest merged band.

---

## 3. European sardine — *Sardina pilchardus*

**Modes: `adult`. Region overrides: required (this species is the reason `byRegion` replaces `sstCByRegion`).**

| Variable | Region / season | Value | Tier | Source |
|---|---|---|---|---|
| SST | Aegean, June | < 22 °C | cited | Tugores et al. 2011, MEPS 443:181, summer model |
| Depth | Aegean, June | < 65 m | cited | Tugores et al. 2011 |
| SST | Adriatic | 20–26 °C | cited | Tugores et al. 2011, interaction depth×SST |
| Depth | Adriatic | < 110 m | cited | Tugores et al. 2011 |
| SST | Spanish, autumn | 14–17 °C (with SLA −5…0 cm) | cited | Tugores et al. 2011, SLA×SST interaction |
| SST | Spanish, autumn | 16–18.5 °C (with SLA 1…5 cm) | cited | Tugores et al. 2011 |
| CHL | Spanish, autumn | 0.45–4.5 mg/m³ | cited | Tugores et al. 2011 |
| Depth | Spanish, autumn | < 90 m | cited | Tugores et al. 2011 |
| SST | spawning (Med) | 12–16 °C, up to 19 °C Catalan; 17–19 °C Aegean | cited | Palomera et al. 2007; Somarakis et al. 2006 via Tugores 2011 |
| Depth | spawning | < 110 m | cited | Tugores et al. 2011 (December egg model) |
| Spawning trigger | SST < 16 °C, November–April | — | cited | Gkanasos et al. 2021, 3D life-cycle model parameterisation |
| Depth | Strait of Sicily | 30–75 m | cited | Schismenou et al. 2014, PLOS ONE 9(7):e101498 |
| Depth | North Aegean | 10–50 m | cited | Schismenou et al. 2014 |
| BL salinity | Aegean | selects < 38, avoids > 38.4 | cited | Schismenou et al. 2014 |
| Eddy kinetic energy | Strait of Sicily | avoids > 200 cm²/s⁻² | cited | Schismenou et al. 2014: sardine selects lower KE, i.e. more coastal, weaker currents |

**Required factors: `depth`, `sst`.**

**Unscorable:** bottom-layer salinity. Schismenou et al. document sardine actively *avoiding* BL salinity above 38.4 while tolerating up to 38.5 in the upper layer — an avoidance band is not expressible as a positive score, and it is exactly the kind of thing that must be declared rather than approximated. Also unscorable: PAR (< 55 E m⁻² d⁻¹ selected, Schismenou 2014) and coast distance (< 250 km for egg retention, Frontiers 2022 13:956654).

**Region matters more here than for any other species in the pilot.** The existing `sstCByRegion` mechanism already exists for sardinella's ~5 °C Maghreb difference; sardine needs the same treatment across four basins with different seasonal optima. This is the case that forces `byRegion` to generalise beyond a single SST field.

---

## 4. Common octopus — *Octopus vulgaris*

**Modes: `foraging` (default) and `spawning`.**

A cephalopod whose documented spawning behaviour is the *opposite* of where the fish are. The v1 profile scored octopus on depth and SST alone, which is a statement about being in the water column rather than about octopus.

| Variable | Mode | Value | Tier | Source |
|---|---|---|---|---|
| Depth | spawning | ~20 m, protect band 5–30 m | cited | Guerra et al. 2015, *Prog. Oceanogr.* 129:36–47 (93 visual censuses, 123.69 ha, Cíes Islands) |
| Substrate | spawning | hard bottom, crevices | cited | Guerra et al. 2015: "noteworthy preference for spawning in areas with hard bottom substrate"; den ecology Hanlon & Messenger 1996, Mangold 1983 |
| Depth | foraging | 20–200 m | cited | Guerra et al. 2015 review: fished 20–200 m in NE Atlantic and Mediterranean |
| Depth | foraging | 20–50 m (trap fishery) | cited | Mereu et al. 2015, mark–recapture, 1604 specimens, Sardinia |
| Depth | juveniles | 18–111 m, peak 40–60 m | cited | Dridi et al. 2023, six-year GAM, southern Morocco |
| SST | juveniles | optimum ~21 °C | cited | Dridi et al. 2023: highest juvenile abundance at SST ≈ 20.7–21 °C |
| Salinity | juveniles | 35.8–37 psu; low salinity fatal | cited | Dridi et al. 2023; Vaz-Pires et al. 2004; Hermosilla et al. 2011 |
| CHL | juveniles | negative below 2.5 mg/m³, positive above | cited | Dridi et al. 2023, autumn GAM: non-monotonic relationship |
| Site fidelity | — | < 1 km in 84–86 % of recaptures | cited | Mereu et al. 2015 |
| Recruitment | — | positive correlation with SST at hatching | cited | Garofalo et al. 2010, ICES JMS 67:1363–1371, Strait of Sicily 1994–2008 |

**Required factors:** `foraging` → `depth`, `sst`. `spawning` → `depth`, `sst`.

**Unscorable:** **hard substrate** (Guerra et al. — the single strongest predictor of spawning dens, and PFZ cannot see it) and **surface dissolved oxygen** (Guerra et al. 2015 review: occurrence varies with SBT, SBS, surface DO, surface CHL-a and coarse sediment). These are named in `unscorableRequirements` permanently.

**Honest consequence:** an octopus spawning zone is scored on depth and temperature while being blind to the factor that decided it. That is disclosed, not hidden. Note the site-fidelity result: octopus do not move far, so a "zone" for this species is a site, not a drifting patch — which matters for the GPX work later (a 1 km radius, not a 20 km polygon).

---

## 5. Red shrimp — *Aristeeomorpha foliacea* / *Plesiopenaeus longirostris*

**Modes: `adult`.**

The most spatially restricted species in the pilot, and the clearest case where a basin-scale band would be actively wrong.

| Variable | Region | Value | Tier | Source |
|---|---|---|---|---|
| Depth | Sardinia / central-western | 400–600 m (peak) | cited | Maiorano et al. 2020, *Adv. Oceanogr. Limnol.* 2020:9471, MEDITS 2009–2014 + GAM |
| Bottom temperature | Sardinia | 13.6–13.8 °C | cited | Maiorano et al. 2020 |
| Bottom salinity | Sardinia | 38.1–38.5 psu | cited | Maiorano et al. 2020 |
| Water mass | — | Levantine Intermediate Water | cited | Maiorano et al. 2020: distribution tightly coupled to LIW |
| Depth | Mediterranean overall | peaks 300–800 m | cited | Ragonese et al. 1997; Politou et al. 2004 via Fiorentino et al. 2013 (FAO MedSudMed) |
| Depth | full range | 120–1300 m | cited | Fiorentino et al. 2013: deep-water benthopelagic, muddy bottoms |
| Depth | Sicilian Channel | 500–700 m | cited | Fiorentino et al. 2013 |
| Depth | Pantelleria Channel | only consistently below 600 m | cited | Bianchini 1999 via Fiorentino et al. 2013 |
| Preferred temperature | basin-wide | ~13 °C | cited | Ghidalia & Bourgois 1961; Yahiaoui 1994; Politou et al. 2004 via Maiorano 2020 |
| Distribution | present | Sardinia, N/C Tyrrhenian, Strait of Sicily, Ionian, Malta | cited | Ragonese & Bianchini 1995; Papaconstantinou & Kapiris 2003 via Maiorano 2020 |
| Distribution | **absent** | Ligurian, Catalan, Balearic, eastern Med | cited | Ragonese & Bianchini 1995; Papaconstantinou & Kapiris 2003 via Maiorano 2020 |
| Bottom | — | mud / soft mud | cited | FAO 2020 Aristeidae catalogue; Fischer et al. 1987 |
| Diel behaviour | — | ascends to shallower depths at night, esp. winter | cited | Cau & Deiana 1982; Kapiris et al. 2010 via Maiorano 2020 |

**Required factors: `depth`, `bottom_temp`, `bottom_salinity`.**

**This species is the strongest argument for geographic regions.** A 13.6–13.8 °C bottom band and a 38.1–38.5 psu bottom band describe *Levantine Intermediate Water*, which only exists as a water mass in the central and eastern Mediterranean. Applied basin-wide, the band would score the Ligurian and Catalan seas as prime red shrimp ground on temperature alone — a confident, wrong, expensive answer. The `absent` distribution row is therefore encoded as a **region gate**, not as a note: `byRegion` carries a hard exclusion for the western sub-basins, and a spot in the Catalan Sea returns `unavailable` with the reason, not a 70.

**Unscorable:** substrate. The species is a mud specialist and "bottom mud" appears in every source, but there is no substrate data in this build. Note that this is a *positive* requirement (muddy) rather than octopus's *avoidance* (hard), which means the score is optimistic rather than pessimistic — worth stating in the coverage note.

---

## What is still missing across the pilot

| Gap | Affects | Status |
|---|---|---|
| Substrate type | octopus, red shrimp, sole, seabream | No source. `unscorableRequirements`. |
| Dissolved oxygen | octopus, benthic scavengers | No source. `unscorableRequirements`. |
| PAR / euphotic depth | sardine, anchovy | No source. `unscorableRequirements`. |
| Coast distance | sardine egg retention | No coastline dataset. Could be approximated from GEBCO but a depth threshold is not a distance — not doing it. |
| Zooplankton, primary production | all small pelagics | No source. |
| Eddy kinetic energy | sardine | Derivable from the SSH grid we already fetch (`KE = ½(u² + v²)` at the surface from ADT gradients) — deferred, would be a real addition, not a proxy. |
| 30-day SST series | bluefin `sst_warming` | One extra cached daily fetch per box. Feasible. |
| Monthly SSH climatology | bluefin `ssh_anomaly` | One extra cached monthly fetch per box. Feasible. |

## Species not yet covered (22 remaining)

`little_tunny`, `swordfish`, `sardinella` (has partial region work), `anchovy`, `horse_mackerel`, `scomber`, `bogue`, `common_dentex`, `common_pandora`, `dusky_grouper`, `white_grouper`, `european_conger`, `european_seabass`, `gilthead_seabream`, `white_seabream`, `red_mullet`, `striped_red_mullet`, `red_scorpiofish`, `common_sole`, `grey_mullet`, `common_cuttlefish`, `deep_rose_shrimp`.

Directional evidence gathered but not yet normalised into rows:
- **Small pelagics generally** (Schismenou 2014, Front. Mar. Sci. 2017 4:230): SST alone is insufficient; UML/BL temperature and salinity, bathymetry and stratification index all matter, and anchovy and sardine differ from each other as much as either differs from mackerel.
- **Horse mackerel** (Front. Mar. Sci. 2017 4:230): bathymetry, SST, sea level anomaly and zonal absolute geostrophic velocity — a different variable set from sardine.
- **Cephalopods generally**: bottom temperature and salinity, surface DO and CHL, hard substrate.
- **Demersal nursery** (PLOS ONE 2014): shelf-break structure plus bottom temperature, salinity and current — the same shape as the hake row.

These will be added species by species, applying the same table structure. No guild-level defaults.
