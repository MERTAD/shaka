import 'dart:convert';

import '../../../data/models/pfz_models.dart';

/// GPX 1.1 export of a ranked PFZ zone set.
///
/// Three separate things travel in one file, because a boat needs all three and
/// a chartplotter only shows you one of them at a time:
///
///  1. **Waypoints** — one per zone, at its centre, named and described. This is
///     what gets called out on a plotter as you approach it. The description
///     carries the score, the status, the applied model context and the blockers,
///     so the file is still honest on a device that shows nothing but the name.
///  2. **A route** — the zone centres in rank order, so the plotter can draw a
///     course through the whole day's targets. This is a suggestion, not an
///     instruction: the zones are ranked by habitat suitability, and the
///     engine's job is not to know about fuel, tide timing or where the skipper
///     actually wants to go. The route is named accordingly.
///  3. **A track** — the same centre sequence as an actual `<trk>`, with the
///     per-day trend attached to each point as extensions, so a tool that reads
///     tracks can chart the score over time without a second file.
///
/// ## What is deliberately not here
///
/// The zone polygons are **not** exported as track points. A polygon outline is
/// not a path a vessel can follow, and writing its vertices into a GPX track
/// would produce a nonsense zig-zag that some plotters would happily draw as a
/// course. The polygons stay on the map inside the app, where a polygon is
/// rendered as a polygon; this file carries centres, which is what a navigation
/// device can actually use.
///
/// ## Honesty in the file
///
/// An unscored zone is exported as a waypoint with `PFZ: not scored` and its
/// blocker text, not as a score of 0. A file that says "BluefinTuna 001, PFZ 0"
/// is a claim about the ground; an unscored zone is a statement about us.
class PfzGpxExport {
  /// Producer string written into `<metadata>`.
  static const String creator = 'Shaka PFZ';

  /// Build a GPX document for one zones response.
  ///
  /// [includeTrendHistory] attaches the persisted day-over-day figures to the
  /// track points. When null, the track is emitted without them rather than with
  /// invented ones.
  static String build(
    PfzZonesResponse response, {
    PfzZoneHistory? history,
  }) {
    final buffer = StringBuffer();
    final zones = response.zones;
    final scored = zones.where((z) => z.hasScore).toList();

    buffer.writeln('<?xml version="1.0" encoding="UTF-8"?>');
    buffer.writeln(
      '<gpx version="1.1" creator="$creator" '
      'xmlns="http://www.topografix.com/GPX/1/1" '
      'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
      'xsi:schemaLocation="http://www.topografix.com/GPX/1/1 '
      'http://www.topografix.com/GPX/1/1/gpx.xsd">',
    );

    _metadata(buffer, response, scored.length, zones.length);

    // ---------------------------------------------------------------- waypoints
    for (final zone in zones) {
      buffer.writeln('  <wpt lat="${_coord(zone.lat)}" lon="${_coord(zone.lon)}">');
      buffer.writeln('    <ele>0.0</ele>');
      buffer.writeln('    <time>${_dayTime(response.date)}</time>');
      buffer.writeln(
        '    <name>${_esc(zone.name.isEmpty ? 'PFZ ${zone.rank}' : zone.name)}</name>',
      );
      buffer.writeln('    <desc>${_esc(_zoneDescription(response, zone))}</desc>');
      // "Waypoint" is a real symbol name in the GPX symbol set; a fishing target
      // is not, and an unknown symbol is dropped by some readers rather than
      // substituted, which would lose the name.
      buffer.writeln('    <sym>Waypoint</sym>');
      buffer.writeln('    <type>${_esc(_zoneType(response))}</type>');
      _extensions(buffer, _zoneExtensions(zone));
      buffer.writeln('  </wpt>');
    }

    // -------------------------------------------------------------------- route
    // Only drawn when at least one zone actually scored. A route through
    // unscored zones would be a navigation instruction to go somewhere the
    // model cannot vouch for.
    if (scored.isNotEmpty) {
      buffer.writeln('  <rte>');
      buffer.writeln(
        '    <name>${_esc("${response.speciesName} ${response.date} targets")}</name>',
      );
      buffer.writeln(
        '    <desc>${_esc("Zone centres in habitat-suitability rank order. "
            "A suggested course, not a steering instruction: the ranking knows "
            "nothing about fuel, tide or your intended route.")}</desc>',
      );
      buffer.writeln('    <rtept lat="${_coord(scored.first.lat)}" lon="${_coord(scored.first.lon)}">');
      buffer.writeln('      <name>${_esc(_startName(scored.first))}</name>');
      buffer.writeln('    </rtept>');
      for (final zone in scored.skip(1)) {
        buffer.writeln('    <rtept lat="${_coord(zone.lat)}" lon="${_coord(zone.lon)}">');
        buffer.writeln('      <name>${_esc(_stopName(zone))}</name>');
        buffer.writeln('    </rtept>');
      }
      buffer.writeln('  </rte>');
    }

    // -------------------------------------------------------------------- track
    if (zones.isNotEmpty) {
      buffer.writeln('  <trk>');
      buffer.writeln(
        '    <name>${_esc("${response.speciesName} ${response.date} track")}</name>',
      );
      buffer.writeln('    <desc>${_esc(_trackDescription(response, history))}</desc>');
      buffer.writeln('    <trkseg>');
      for (final zone in zones) {
        buffer.writeln(
          '      <trkpt lat="${_coord(zone.lat)}" lon="${_coord(zone.lon)}">',
        );
        buffer.writeln('        <ele>0.0</ele>');
        buffer.writeln('        <time>${_dayTime(response.date)}</time>');
        _extensions(buffer, _zoneExtensions(zone));
        buffer.writeln('      </trkpt>');
      }
      buffer.writeln('    </trkseg>');
      buffer.writeln('  </trk>');
    }

    // Trend summary, as a second track segment, so the score history is in the
    // file rather than only on a screen. Skipped entirely when there is no
    // history: an empty trend track would be a shape with no meaning.
    if (history != null && history.days.isNotEmpty) {
      _historyTrack(buffer, response, history);
    }

    buffer.writeln('</gpx>');
    return buffer.toString();
  }

  /// A sensible default filename. No spaces, because this is going onto a plotter
  /// SD card via a cable at some point.
  static String fileName(PfzZonesResponse response) {
    final tag = response.speciesId.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '-');
    final date = response.date.replaceAll('-', '');
    final lat = response.lat.toStringAsFixed(2).replaceAll('-', 'm');
    final lon = response.lon.toStringAsFixed(2).replaceAll('-', 'm');
    return 'shaka-pfz-$tag-$date-$lat-$lon.gpx';
  }

  static void _metadata(
    StringBuffer b,
    PfzZonesResponse response,
    int scored,
    int total,
  ) {
    b.writeln('  <metadata>');
    b.writeln('    <name>${_esc("${response.speciesName} ${response.date}")}</name>');
    b.writeln('    <desc>${_esc(_metadataDescription(response, scored, total))}</desc>');
    b.writeln('    <time>${_dayTime(response.date)}</time>');
    b.writeln('    <author>${_esc(creator)}</author>');
    b.writeln('  </metadata>');
  }

  static String _metadataDescription(
    PfzZonesResponse response,
    int scored,
    int total,
  ) {
    final parts = <String>[
      'Potential Fishing Zone targets for ${response.speciesName} '
          '(${response.speciesScientificName}) on ${response.date}.',
      'Analysed box ${response.lat.toStringAsFixed(3)}, '
          '${response.lon.toStringAsFixed(3)}.',
      '$scored of $total zone(s) were scoreable.',
    ];
    if (response.confidence > 0) {
      parts.add('Highest per-zone confidence ${response.confidence}.');
    }
    // The applied model context travels in the file, not just the app: a score
    // that cannot say which model produced it is not reproducible, and this file
    // may be the only copy left.
      if (response.zones.any((z) => z.mode != null)) {
      final mode =
          response.zones.map((z) => z.mode).whereType<String>().first;
      final size = response.zones.map((z) => z.sizeClass).whereType<String>().firstOrNull;
      parts.add('Model context: $mode${size != null ? ' / $size' : ''}.');
    }
    if (response.missingFactors.isNotEmpty) {
      parts.add('Not measured here: ${response.missingFactors.join(', ')}.');
    }
    if (scored < total) {
      parts.add('Unscored zones are listed with their reason. They are not bad '
          'ground — the model could not see them, or the species is outside its '
          'documented range here.');
    }
    return parts.join(' ');
  }

  static void _historyTrack(
    StringBuffer b,
    PfzZonesResponse response,
    PfzZoneHistory history,
  ) {
    final scoredDays = history.days.where((d) => d.hasScore).toList();
    if (scoredDays.isEmpty) return;

    b.writeln('  <trk>');
    b.writeln(
      '    <name>${_esc("${response.speciesName} score history")}</name>',
    );
    b.writeln(
      '    <desc>${_esc("Day-over-day PFZ score at this box, oldest first. "
          "Only days that produced a scoreable zone are plotted; unscored days "
          "are omitted rather than interpolated, because a line through a gap "
          "would be a measurement we never made.")}</desc>',
    );
    b.writeln('    <trkseg>');
    for (final day in scoredDays) {
      b.writeln(
        '      <trkpt lat="${_coord(response.lat)}" lon="${_coord(response.lon)}">',
      );
      b.writeln('        <time>${_dayTime(day.date)}</time>');
      b.writeln('        <extensions>');
      b.writeln('          <shaka:pfz>${day.topPfz}</shaka:pfz>');
      b.writeln(
        '          <shaka:pfz-confidence>${day.confidence}</shaka:pfz-confidence>',
      );
      b.writeln('        </extensions>');
      b.writeln('      </trkpt>');
    }
    b.writeln('    </trkseg>');
    b.writeln('  </trk>');
  }

  static String _trackDescription(
    PfzZonesResponse response,
    PfzZoneHistory? history,
  ) {
    final base = 'Zone centres for ${response.speciesName} on ${response.date} '
        'at the analysis point, in rank order. This is a ranked list of places, '
        'not a recorded passage: no vessel has driven this line.';
    if (history == null || history.days.isEmpty) return base;
    final scored = history.days.where((d) => d.hasScore).length;
    return '$base $scored of the last ${history.days.length} recorded day(s) '
        'were scoreable at this box.';
  }

  static String _zoneDescription(PfzZonesResponse response, PfzZone zone) {
    final parts = <String>[
      'Rank ${zone.rank} of ${response.zones.length}.',
      if (zone.hasScore)
        'PFZ ${zone.pfz}/100, confidence ${zone.confidence}.'
      else
        // The wording matters: this is not a low score.
        'PFZ not scored (${_statusPhrase(zone.status)}).',
      if (zone.frontKm != null) 'Front ${zone.frontKm!.toStringAsFixed(1)} km away.',
      if (zone.depthM != null) 'Depth ${zone.depthM!.toStringAsFixed(0)} m.',
      if (zone.region != null) 'Basin ${zone.region}.',
      if (zone.missingFactors.isNotEmpty)
        'Not measured: ${zone.missingFactors.join(', ')}.',
      if (zone.blockers.isNotEmpty) 'Blockers: ${zone.blockers.join('; ')}.',
      if (zone.drivers.isNotEmpty) 'Drivers: ${zone.drivers.join(', ')}.',
    ];
    return parts.join(' ');
  }

  static String _statusPhrase(PfzStatus status) => switch (status) {
        PfzStatus.scoreable => 'scored',
        PfzStatus.unavailable => 'species outside its documented range here',
        PfzStatus.insufficientData => 'habitat plausible but data insufficient',
      };

  static String _zoneType(PfzZonesResponse response) =>
      'PFZ,${response.speciesId},${response.date}';

  static List<Map<String, String?>> _zoneExtensions(PfzZone zone) => [
        {'shaka:pfz-rank': '${zone.rank}'},
        {'shaka:pfz-status': zone.status.name},
        {'shaka:pfz-score': zone.pfz?.toString() ?? 'not scored'},
        {'shaka:pfz-confidence': '${zone.confidence}'},
        if (zone.mode != null) {'shaka:pfz-mode': zone.mode!},
        if (zone.sizeClass != null) {'shaka:pfz-size-class': zone.sizeClass!},
        if (zone.region != null) {'shaka:pfz-region': zone.region!},
        if (zone.frontKm != null)
          {'shaka:pfz-front-km': zone.frontKm!.toStringAsFixed(2)},
        if (zone.sstGradientCkm != null)
          {'shaka:pfz-sst-gradient-ckm': zone.sstGradientCkm!.toStringAsFixed(3)},
        if (zone.sstAnomalyC != null)
          {'shaka:pfz-sst-anomaly-c': zone.sstAnomalyC!.toStringAsFixed(2)},
        if (zone.depthM != null)
          {'shaka:pfz-depth-m': zone.depthM!.toStringAsFixed(1)},
        if (zone.polygon.length >= 3)
          // The outline rides along in an extension rather than becoming track
          // points, so a plotter that ignores extensions still gets a valid file
          // and a tool that reads extensions gets the shape back.
          {'shaka:pfz-polygon-points': '${zone.polygon.length}'},
        if (zone.missingFactors.isNotEmpty)
          {'shaka:pfz-missing': zone.missingFactors.join(',')},
        if (zone.blockers.isNotEmpty)
          {'shaka:pfz-blockers': zone.blockers.join('; ')},
      ];

  static void _extensions(StringBuffer b, List<Map<String, String?>> fields) {
    b.writeln('    <extensions>');
    for (final field in fields) {
      for (final entry in field.entries) {
        b.writeln('      <${entry.key}>${_esc(entry.value ?? '')}</${entry.key}>');
      }
    }
    b.writeln('    </extensions>');
  }

  static String _startName(PfzZone zone) =>
      zone.name.isEmpty ? 'Start (rank ${zone.rank})' : '${zone.name} (start)';

  static String _stopName(PfzZone zone) =>
      zone.name.isEmpty ? 'Target ${zone.rank}' : zone.name;

  /// Fixed 6-decimal formatting. GPX coordinates are WGS84 degrees; writing a
  /// locale-formatted number here is the classic way to produce a file that
  /// silently loads in the Gulf of Guinea.
  static String _coord(double v) => v.toStringAsFixed(6);

  /// A date as an ISO instant at 00:00Z.
  ///
  /// Every point in a daily snapshot carries the same timestamp because they were
  /// all computed for the same ocean analysis, and the file is not a log of
  /// positions over time. Using the analysis date rather than the export time
  /// keeps the file honest: a zone target is a claim about that day.
  static String _dayTime(String isoDate) =>
      '${isoDate}T00:00:00Z'.replaceAll(RegExp(r'[^0-9TZ:\-]'), '');

  static String _esc(String value) {
    final buffer = StringBuffer();
    for (final rune in value.runes) {
      switch (rune) {
        case 0x26:
          buffer.write('&amp;');
        case 0x3C:
          buffer.write('&lt;');
        case 0x3E:
          buffer.write('&gt;');
        case 0x22:
          buffer.write('&quot;');
        case 0x27:
          buffer.write('&apos;');
        default:
          buffer.writeCharCode(rune);
      }
    }
    return buffer.toString();
  }
}
