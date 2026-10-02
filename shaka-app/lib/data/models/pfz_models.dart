// Client models for `GET /v1/spots/{id}/pfz` — species-specific Potential
// Fishing Zone scoring.
//
// These mirror the Kotlin `com.shaka.pfz` response types. The important
// detail is the status/pfz contract:
//
//  * `pfz` is null unless `status` is [PfzStatus.scoreable].
//  * `unavailable` means the species is outside its documented habitat here.
//    That is NOT "scored badly" and must never be rendered as 0.
//  * `insufficientData` means the habitat is plausible but we lack the data
//    to say. Also never 0.
//
// A UI that collapses these three into a number is lying to the angler, which
// is the exact failure this endpoint was built to avoid.

/// Provenance of a factor's thresholds, as reported by the backend.
enum PfzFactorConfidence {
  /// Traceable to a published source.
  cited,

  /// Standard fisheries knowledge, not traceable to a specific citation.
  expert,

  /// Not researched; the factor is excluded and reported as missing.
  unknown;

  static PfzFactorConfidence parse(String? raw) {
    return PfzFactorConfidence.values.firstWhere(
      (e) => e.name == raw,
      orElse: () => PfzFactorConfidence.unknown,
    );
  }
}

/// Outcome of evaluating one species against one spot and date.
enum PfzStatus {
  /// A defensible 0-100 score was produced; see `pfz`.
  scoreable,

  /// The species is outside its documented habitat here. `pfz` is null.
  unavailable,

  /// Habitat is plausible but data is insufficient. `pfz` is null.
  insufficientData;

  bool get hasScore => this == PfzStatus.scoreable;

  static PfzStatus parse(String? raw) {
    return switch (raw) {
      'scoreable' => PfzStatus.scoreable,
      'unavailable' => PfzStatus.unavailable,
      'insufficient_data' => PfzStatus.insufficientData,
      _ => PfzStatus.insufficientData,
    };
  }
}

/// One factor's contribution to a species score.
class PfzFactorScore {
  final String factor;
  final int score;

  /// Renormalized weight actually used, after unmeasured factors were dropped.
  final double weight;
  final PfzFactorConfidence confidence;

  const PfzFactorScore({
    required this.factor,
    required this.score,
    required this.weight,
    required this.confidence,
  });

  factory PfzFactorScore.fromJson(Map<String, dynamic> json) {
    return PfzFactorScore(
      factor: json['factor'] as String? ?? 'unknown',
      score: (json['score'] as num?)?.toInt() ?? 0,
      weight: (json['weight'] as num?)?.toDouble() ?? 0.0,
      confidence:
          PfzFactorConfidence.parse(json['confidence'] as String?),
    );
  }

  /// Human-readable label. The backend sends stable factor keys, so the UI can
  /// rely on these rather than on index positions.
  String get label => switch (factor) {
        'sst' => 'Sea temperature',
        'chl' => 'Chlorophyll',
        'chla_gradient' => 'Chlorophyll front',
        'depth' => 'Depth',
        'wind' => 'Wind',
        'swell' => 'Swell',
        'current' => 'Current',
        'solunar' => 'Moon & tide',
        'season' => 'Season',
        _ => factor,
      };
}

/// Per-species verdict.
class PfzSpeciesResult {
  final String id;
  final String commonName;
  final String scientificName;
  final String guild;
  final PfzStatus status;

  /// 0-100, or null unless [status] is [PfzStatus.scoreable].
  final int? pfz;

  /// 0-100, reduced by missing data, low-confidence thresholds and forecast
  /// distance. Independent of [pfz]: a high score at low confidence is a weak
  /// claim and should be presented as one.
  final int confidence;
  final List<PfzFactorScore> factors;

  /// Factors that pushed the score up, most important first.
  final List<String> drivers;

  /// Why the species is unavailable. Empty when scoreable.
  final List<String> blockers;

  /// Factors the profile wants that the observation could not supply.
  final List<String> missingFactors;

  /// Factors scored on expert rather than cited thresholds.
  final List<String> lowConfidenceFactors;

  /// Known habitat requirements the app has no data source for (e.g. hard
  /// bottom substrate for octopus). The score is blind to these.
  final List<String> unscorableRequirements;

  /// The model that produced this number.
  ///
  /// Bluefin feeding and spawning are near-inverted, so a score without the mode
  /// that generated it is not reproducible — and the caller has to be able to
  /// show which one they are looking at.
  final String? mode;

  /// Size class the applied mode was parameterised for, when it is split.
  final String? sizeClass;

  /// Macro-basin used for geographic overrides, when one was resolved.
  final String? region;

  /// Species-level caveat that must be shown next to the number, such as
  /// "no Maghreb band is cited, so the basin-wide band is being applied here".
  final String? note;

  const PfzSpeciesResult({
    required this.id,
    required this.commonName,
    required this.scientificName,
    required this.guild,
    required this.status,
    this.pfz,
    this.confidence = 0,
    this.factors = const [],
    this.drivers = const [],
    this.blockers = const [],
    this.missingFactors = const [],
    this.lowConfidenceFactors = const [],
    this.unscorableRequirements = const [],
    this.mode,
    this.sizeClass,
    this.region,
    this.note,
  });

  factory PfzSpeciesResult.fromJson(Map<String, dynamic> json) {
    List<String> strings(String key) =>
        (json[key] as List?)?.map((e) => e.toString()).toList() ?? const [];

    return PfzSpeciesResult(
      id: json['id'] as String? ?? 'unknown',
      commonName: json['commonName'] as String? ?? 'Unknown',
      scientificName: json['scientificName'] as String? ?? '',
      guild: json['guild'] as String? ?? 'unknown',
      status: PfzStatus.parse(json['status'] as String?),
      // Deliberately NOT defaulted to 0. A missing score is a missing score.
      pfz: (json['pfz'] as num?)?.toInt(),
      confidence: (json['confidence'] as num?)?.toInt() ?? 0,
      factors: (json['factors'] as List? ?? const [])
          .map((e) => PfzFactorScore.fromJson(e as Map<String, dynamic>))
          .toList(),
      drivers: strings('drivers'),
      blockers: strings('blockers'),
      missingFactors: strings('missingFactors'),
      lowConfidenceFactors: strings('lowConfidenceFactors'),
      unscorableRequirements: strings('unscorableRequirements'),
      mode: json['mode'] as String?,
      sizeClass: json['sizeClass'] as String?,
      region: json['region'] as String?,
      note: json['note'] as String?,
    );
  }

  /// True when at least one real requirement of this species is invisible to the
  /// app. Such a verdict should never be presented without its caveat.
  bool get isBlindToSomething => unscorableRequirements.isNotEmpty;
}

/// Full PFZ response for one spot and date.
class PfzResponse {
  final String spotId;
  final String date;

  /// Highest per-species confidence in this response.
  final int confidence;
  final List<PfzSpeciesResult> species;

  /// Factors no spot in scope could supply. A data gap, not a bad forecast.
  final List<String> missingFactors;

  /// Honest statement of what the spot inventory cannot yet represent
  /// (e.g. no Gulf of Lions spots, so bluefin is systematically pessimistic).
  final List<String> coverageNotes;

  const PfzResponse({
    required this.spotId,
    required this.date,
    required this.confidence,
    required this.species,
    this.missingFactors = const [],
    this.coverageNotes = const [],
  });

  factory PfzResponse.fromJson(Map<String, dynamic> json) {
    return PfzResponse(
      spotId: json['spotId'] as String? ?? 'unknown',
      date: json['date'] as String? ?? '',
      confidence: (json['confidence'] as num?)?.toInt() ?? 0,
      species: (json['species'] as List? ?? const [])
          .map((e) => PfzSpeciesResult.fromJson(e as Map<String, dynamic>))
          .toList(),
      missingFactors: (json['missingFactors'] as List?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
      coverageNotes: (json['coverageNotes'] as List?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
    );
  }

  /// Species that produced a real score, best first.
  List<PfzSpeciesResult> get scoreable =>
      species.where((s) => s.status.hasScore && s.pfz != null).toList();

  /// Species that are present-and-known but the data cannot support.
  List<PfzSpeciesResult> get insufficient =>
      species.where((s) => s.status == PfzStatus.insufficientData).toList();

  /// Species genuinely outside their habitat at this spot.
  List<PfzSpeciesResult> get unavailable =>
      species.where((s) => s.status == PfzStatus.unavailable).toList();

  /// The best-scoring species, or null when nothing could be scored.
  PfzSpeciesResult? get best => scoreable.isEmpty ? null : scoreable.first;
}

/// A single lat/lon vertex of a zone polygon, mirroring `PfzPoint` on the wire.
class PfzPoint {
  final double lat;
  final double lon;

  const PfzPoint({required this.lat, required this.lon});

  factory PfzPoint.fromJson(Map<String, dynamic> json) => PfzPoint(
        lat: (json['lat'] as num?)?.toDouble() ?? 0.0,
        lon: (json['lon'] as num?)?.toDouble() ?? 0.0,
      );

  /// maplibre order is `[lon, lat]`.
  List<double> get asLngLat => [lon, lat];
}

/// One ranked candidate fishing zone, drawn as a data-shaped polygon and a
/// SatCatch-style labelled point at its centre. Mirrors `PfzZone` on the wire.
class PfzZone {
  final int rank;

  /// Satellite-style target label, e.g. "BluefinTuna 28/09 001".
  final String name;
  final double lat;
  final double lon;

  /// Outer ring of the zone polygon. Empty when the grid did not resolve.
  final List<PfzPoint> polygon;

  /// Interior rings (holes) of [polygon], when the patch contains any.
  final List<List<PfzPoint>> holes;
  final PfzStatus status;

  /// 0-100, or null unless [status] is scoreable. Never defaulted to 0.
  final int? pfz;
  final int confidence;
  final double? frontKm;
  final String? frontCoincidence;
  final double? sstGradientCkm;
  final double? chlaGradientMgM3km;
  final double? sstAnomalyC;
  final double? depthM;

  /// The model that produced this zone's number: the behavioural mode, the size
  /// class, and the macro-basin the zone resolved to.
  ///
  /// The same corridor can be run for two modes of one species and the scores
  /// differ, so a zone card without this cannot be reproduced. The region also
  /// decides whether the score exists at all — a red shrimp zone in the western
  /// basin is refused, not ranked low.
  final String? mode;
  final String? sizeClass;
  final String? region;

  final List<String> drivers;
  final List<String> blockers;
  final List<String> missingFactors;
  final List<String> lowConfidenceFactors;

  const PfzZone({
    required this.rank,
    required this.name,
    required this.lat,
    required this.lon,
    this.polygon = const [],
    this.holes = const [],
    required this.status,
    this.pfz,
    this.confidence = 0,
    this.frontKm,
    this.frontCoincidence,
    this.sstGradientCkm,
    this.chlaGradientMgM3km,
    this.sstAnomalyC,
    this.depthM,
    this.mode,
    this.sizeClass,
    this.region,
    this.drivers = const [],
    this.blockers = const [],
    this.missingFactors = const [],
    this.lowConfidenceFactors = const [],
  });

  factory PfzZone.fromJson(Map<String, dynamic> json) {
    List<String> strings(String key) =>
        (json[key] as List?)?.map((e) => e.toString()).toList() ?? const [];

    return PfzZone(
      rank: (json['rank'] as num?)?.toInt() ?? 0,
      name: json['name'] as String? ?? '',
      lat: (json['lat'] as num?)?.toDouble() ?? 0.0,
      lon: (json['lon'] as num?)?.toDouble() ?? 0.0,
      polygon: (json['polygon'] as List? ?? const [])
          .map((e) => PfzPoint.fromJson(e as Map<String, dynamic>))
          .toList(),
      holes: (json['holes'] as List? ?? const [])
          .map((e) => (e as List)
              .map((p) => PfzPoint.fromJson(p as Map<String, dynamic>))
              .toList())
          .toList(),
      status: PfzStatus.parse(json['status'] as String?),
      pfz: (json['pfz'] as num?)?.toInt(),
      confidence: (json['confidence'] as num?)?.toInt() ?? 0,
      frontKm: (json['frontKm'] as num?)?.toDouble(),
      frontCoincidence: json['frontCoincidence'] as String?,
      sstGradientCkm: (json['sstGradientCkm'] as num?)?.toDouble(),
      chlaGradientMgM3km: (json['chlaGradientMgM3km'] as num?)?.toDouble(),
      sstAnomalyC: (json['sstAnomalyC'] as num?)?.toDouble(),
      depthM: (json['depthM'] as num?)?.toDouble(),
      mode: json['mode'] as String?,
      sizeClass: json['sizeClass'] as String?,
      region: json['region'] as String?,
      drivers: strings('drivers'),
      blockers: strings('blockers'),
      missingFactors: strings('missingFactors'),
      lowConfidenceFactors: strings('lowConfidenceFactors'),
    );
  }

  /// When scored, a fill colour keyed to the same 0-100 bands the spot map
  /// uses. Unscored zones render as outline-only (they are still real.
  bool get hasScore => status.hasScore && pfz != null;
}

/// Full body for `GET /v1/pfz/zones` — the offshore SatCatch-style targets
/// around one lat/lon for one species and date.
class PfzZonesResponse {
  final String speciesId;
  final String speciesName;
  final String speciesScientificName;
  final String date;
  final double lat;
  final double lon;
  final int confidence;
  final List<PfzZone> zones;
  final List<String> missingFactors;
  final List<String> coverageNotes;

  const PfzZonesResponse({
    required this.speciesId,
    required this.speciesName,
    required this.speciesScientificName,
    required this.date,
    required this.lat,
    required this.lon,
    required this.confidence,
    required this.zones,
    this.missingFactors = const [],
    this.coverageNotes = const [],
  });

  factory PfzZonesResponse.fromJson(Map<String, dynamic> json) {
    return PfzZonesResponse(
      speciesId: json['speciesId'] as String? ?? 'unknown',
      speciesName: json['speciesName'] as String? ?? 'Unknown',
      speciesScientificName: json['speciesScientificName'] as String? ?? '',
      date: json['date'] as String? ?? '',
      lat: (json['lat'] as num?)?.toDouble() ?? 0.0,
      lon: (json['lon'] as num?)?.toDouble() ?? 0.0,
      confidence: (json['confidence'] as num?)?.toInt() ?? 0,
      zones: (json['zones'] as List? ?? const [])
          .map((e) => PfzZone.fromJson(e as Map<String, dynamic>))
          .toList(),
      missingFactors: (json['missingFactors'] as List?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
      coverageNotes: (json['coverageNotes'] as List?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
    );
  }

  /// Zones that can be drawn: those with a real outline.
  List<PfzZone> get drawable =>
      zones.where((z) => z.polygon.length >= 3).toList();
}

/// Full body for `GET /v1/pfz/zones/history` — what the model said about this
/// box on each past day, read from the server's persisted `pfz_zones_daily` rows.
///
/// This is deliberately NOT a re-run of the engine over past dates. Ocean fields
/// get revised after publication and a Copernicus request for a past date can be
/// refused outright once the archive moves on, so recomputing would answer "what
/// would today's model say about yesterday" rather than "what did it say
/// yesterday". Only the stored answer is the second thing.
///
/// Two nulls mean different things and must be rendered differently:
///  * [PfzHistoryDay.topPfz] null — no zone scored that day. The species was
///    outside its habitat, or a required measurement (bottom temperature, bottom
///    salinity) did not resolve. This is a GAP. It is not 0, and a chart must not
///    draw a line through it.
///  * [PfzHistoryCell.change] null — the cell has no comparison to make: a single
///    observation, or an unscored end. Not "unchanged".
class PfzZoneHistory {
  final String speciesId;
  final String speciesName;
  final String speciesScientificName;
  final double anchorLat;
  final double anchorLon;

  /// The model context these rows were scored under. A bluefin feeding series
  /// and a spawning series are separate measurements and never interleave.
  final String mode;
  final String sizeClass;

  final String fromDate;
  final String toDate;

  /// Best score on the most recent day that had one, scanning back over unscored
  /// days rather than reporting 0 for the newest one.
  final int? latestTopPfz;

  /// Change between the two most recent days that both scored. Null with fewer
  /// than two, which is a real and common state on a new location.
  final int? dayOverDay;

  final List<PfzHistoryDay> days;
  final List<PfzHistoryCell> cells;
  final List<String> coverageNotes;

  const PfzZoneHistory({
    required this.speciesId,
    required this.speciesName,
    required this.speciesScientificName,
    required this.anchorLat,
    required this.anchorLon,
    required this.mode,
    required this.sizeClass,
    required this.fromDate,
    required this.toDate,
    this.latestTopPfz,
    this.dayOverDay,
    this.days = const [],
    this.cells = const [],
    this.coverageNotes = const [],
  });

  factory PfzZoneHistory.fromJson(Map<String, dynamic> json) {
    return PfzZoneHistory(
      speciesId: json['speciesId'] as String? ?? 'unknown',
      speciesName: json['speciesName'] as String? ?? 'Unknown',
      speciesScientificName: json['speciesScientificName'] as String? ?? '',
      anchorLat: (json['anchorLat'] as num?)?.toDouble() ?? 0.0,
      anchorLon: (json['anchorLon'] as num?)?.toDouble() ?? 0.0,
      mode: json['mode'] as String? ?? 'default',
      sizeClass: json['sizeClass'] as String? ?? 'default',
      fromDate: json['fromDate'] as String? ?? '',
      toDate: json['toDate'] as String? ?? '',
      latestTopPfz: (json['latestTopPfz'] as num?)?.toInt(),
      dayOverDay: (json['dayOverDay'] as num?)?.toInt(),
      days: (json['days'] as List? ?? const [])
          .map((e) => PfzHistoryDay.fromJson(e as Map<String, dynamic>))
          .toList(),
      cells: (json['cells'] as List? ?? const [])
          .map((e) => PfzHistoryCell.fromJson(e as Map<String, dynamic>))
          .toList(),
      coverageNotes: (json['coverageNotes'] as List?)
              ?.map((e) => e.toString())
              .toList() ??
          const [],
    );
  }

  /// True when nothing has been persisted for this request yet.
  ///
  /// A new location legitimately starts here, so this is a "not yet" state and
  /// not an error to surface as one.
  bool get isEmpty => days.isEmpty;

  /// Days that produced a scoreable zone, in chronological order.
  List<PfzHistoryDay> get scoredDays =>
      days.where((d) => d.topPfz != null).toList();

  /// Best single day over the window, or null when nothing scored.
  int? get bestPfz => days
      .map((d) => d.topPfz)
      .whereType<int>()
      .fold<int?>(null, (best, v) => best == null || v > best ? v : best);

  /// A one-line, non-alarmist summary for a chart header.
  ///
  /// Says "no scored days" rather than showing a flat 0 line, because a flat line
  /// at zero is a claim about the ground and we have none to make.
  String get summary {
    if (isEmpty) return 'No history yet';
    final scored = scoredDays.length;
    if (scored == 0) {
      return '${days.length} day(s) recorded, none scoreable';
    }
    final delta = dayOverDay;
    final deltaText = delta == null
        ? 'no change to compare'
        : delta > 0
            ? '+$delta vs previous scored day'
            : delta < 0
                ? '$delta vs previous scored day'
                : 'unchanged';
    return '$scored of ${days.length} day(s) scored · $deltaText';
  }
}

/// One persisted day of zone history.
class PfzHistoryDay {
  final String date;

  /// Best scoreable zone that day, or null when none scored. A gap, never 0.
  final int? topPfz;

  /// Confidence of the day's best scored zone; 0 when nothing scored, because a
  /// day we could not score cannot also be claiming high confidence.
  final int confidence;

  final int zoneCount;
  final int scoreableCount;
  final List<PfzHistoryZone> zones;

  const PfzHistoryDay({
    required this.date,
    this.topPfz,
    this.confidence = 0,
    this.zoneCount = 0,
    this.scoreableCount = 0,
    this.zones = const [],
  });

  factory PfzHistoryDay.fromJson(Map<String, dynamic> json) {
    return PfzHistoryDay(
      date: json['date'] as String? ?? '',
      topPfz: (json['topPfz'] as num?)?.toInt(),
      confidence: (json['confidence'] as num?)?.toInt() ?? 0,
      zoneCount: (json['zoneCount'] as num?)?.toInt() ?? 0,
      scoreableCount: (json['scoreableCount'] as num?)?.toInt() ?? 0,
      zones: (json['zones'] as List? ?? const [])
          .map((e) => PfzHistoryZone.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }

  bool get hasScore => topPfz != null;

  /// Day label for a chart axis: "14/07".
  String get shortLabel {
    final parts = date.split('-');
    if (parts.length != 3) return date;
    return '${parts[2]}/${parts[1]}';
  }
}

/// One persisted zone within a day.
class PfzHistoryZone {
  final int rank;
  final String name;
  final double lat;
  final double lon;
  final PfzStatus status;
  final int? pfz;
  final int confidence;
  final double? frontKm;
  final double? depthM;
  final String? region;
  final List<String> drivers;
  final List<String> blockers;
  final List<String> missingFactors;

  const PfzHistoryZone({
    required this.rank,
    required this.name,
    required this.lat,
    required this.lon,
    required this.status,
    this.pfz,
    this.confidence = 0,
    this.frontKm,
    this.depthM,
    this.region,
    this.drivers = const [],
    this.blockers = const [],
    this.missingFactors = const [],
  });

  factory PfzHistoryZone.fromJson(Map<String, dynamic> json) {
    List<String> strings(String key) =>
        (json[key] as List?)?.map((e) => e.toString()).toList() ?? const [];
    return PfzHistoryZone(
      rank: (json['rank'] as num?)?.toInt() ?? 0,
      name: json['name'] as String? ?? '',
      lat: (json['lat'] as num?)?.toDouble() ?? 0.0,
      lon: (json['lon'] as num?)?.toDouble() ?? 0.0,
      status: PfzStatus.parse(json['status'] as String?),
      pfz: (json['pfz'] as num?)?.toInt(),
      confidence: (json['confidence'] as num?)?.toInt() ?? 0,
      frontKm: (json['frontKm'] as num?)?.toDouble(),
      depthM: (json['depthM'] as num?)?.toDouble(),
      region: json['region'] as String?,
      drivers: strings('drivers'),
      blockers: strings('blockers'),
      missingFactors: strings('missingFactors'),
    );
  }
}

/// One grid cell's score across the days it was persisted — the unit a trend is
/// honestly measured in. A cell is a real place inside the box; a rank is not,
/// and ranks are re-assigned on every rebuild.
class PfzHistoryCell {
  final double lat;
  final double lon;
  final int? firstPfz;
  final int? lastPfz;

  /// Last minus first, or null when there is no comparison to make.
  final int? change;

  final List<PfzHistoryPoint> points;

  const PfzHistoryCell({
    required this.lat,
    required this.lon,
    this.firstPfz,
    this.lastPfz,
    this.change,
    this.points = const [],
  });

  factory PfzHistoryCell.fromJson(Map<String, dynamic> json) {
    return PfzHistoryCell(
      lat: (json['lat'] as num?)?.toDouble() ?? 0.0,
      lon: (json['lon'] as num?)?.toDouble() ?? 0.0,
      firstPfz: (json['firstPfz'] as num?)?.toInt(),
      lastPfz: (json['lastPfz'] as num?)?.toInt(),
      change: (json['change'] as num?)?.toInt(),
      points: (json['points'] as List? ?? const [])
          .map((e) => PfzHistoryPoint.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }

  bool get hasChange => change != null;

  /// Cells worth showing in a list: the ones that actually moved, best first.
  static List<PfzHistoryCell> movers(List<PfzHistoryCell> cells) {
    final moving = cells.where((c) => c.change != null).toList()
      ..sort((a, b) => (b.change ?? 0).compareTo(a.change ?? 0));
    return moving;
  }
}

/// One cell observation on one day. [pfz] null means unscored, not zero.
class PfzHistoryPoint {
  final String date;
  final int? pfz;
  final int confidence;
  final int rank;

  const PfzHistoryPoint({
    required this.date,
    this.pfz,
    this.confidence = 0,
    this.rank = 0,
  });

  factory PfzHistoryPoint.fromJson(Map<String, dynamic> json) {
    return PfzHistoryPoint(
      date: json['date'] as String? ?? '',
      pfz: (json['pfz'] as num?)?.toInt(),
      confidence: (json['confidence'] as num?)?.toInt() ?? 0,
      rank: (json['rank'] as num?)?.toInt() ?? 0,
    );
  }
}
