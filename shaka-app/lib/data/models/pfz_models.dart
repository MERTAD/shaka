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
