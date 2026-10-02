import 'package:flutter_test/flutter_test.dart';
import 'package:shaka/data/models/pfz_models.dart';
import 'package:shaka/data/services/pfz_gpx_export.dart';
import 'package:xml/xml.dart';

/// What the export has to get right, in order of how much harm a mistake does:
///
///  1. **The file parses.** A GPX that will not open on a plotter is worthless,
///     and XML is unforgiving: one unescaped `&` in a species name is enough.
///  2. **An unscored zone never becomes a 0.** This is the same contract as the
///     rest of the feature, and export is exactly where it would be lost — a
///     plotter has no way to show "not measured", so the file has to say it in
///     words that survive a name-length-limited screen.
///  3. **Trends omit gaps instead of interpolating them.**
///  4. **The model context travels in the file**, so a score can be reproduced
///     from the file alone.
void main() {
  PfzZone zone({
    required int rank,
    String? name,
    double lat = 36.4,
    double lon = 18.1,
    PfzStatus status = PfzStatus.scoreable,
    int? pfz,
    List<PfzPoint> polygon = const [],
    String? mode = 'feeding',
    String? sizeClass = 'large',
  }) {
    return PfzZone(
      rank: rank,
      name: name ?? 'Zone $rank',
      lat: lat,
      lon: lon,
      status: status,
      pfz: pfz,
      confidence: 60,
      polygon: polygon,
      mode: mode,
      sizeClass: sizeClass,
    );
  }

  PfzZonesResponse response(List<PfzZone> zones) {
    return PfzZonesResponse(
      speciesId: 'bluefin_tuna',
      speciesName: 'Bluefin Tuna',
      speciesScientificName: 'Thunnus thynnus',
      date: '2026-07-14',
      lat: 36.4,
      lon: 18.1,
      confidence: 60,
      zones: zones,
    );
  }

  group('well-formedness', () {
    test('a normal export parses as XML', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, pfz: 82, lat: 36.41, lon: 18.12),
        zone(rank: 2, pfz: 55, lat: 36.35, lon: 18.20),
      ]));

      final doc = XmlDocument.parse(gpx);
      expect(doc.rootElement.name.local, 'gpx');
      expect(doc.rootElement.getAttribute('version'), '1.1');
      expect(doc.findAllElements('wpt'), hasLength(2));
    });

    test('an unscored zone still produces a parsable file', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, pfz: 82),
        zone(
          rank: 2,
          status: PfzStatus.insufficientData,
          pfz: null,
        ),
      ]));

      expect(() => XmlDocument.parse(gpx), returnsNormally);
    });

    test('a species name containing XML metacharacters is escaped', () {
      // Not a hypothetical: "&" in a common-name field is a normal thing to find,
      // and an unescaped one produces a file that no reader will open.
      final res = PfzZonesResponse(
        speciesId: 'sp1',
        speciesName: 'Swordfish & Sailfish <deep>',
        speciesScientificName: "Xiphias gladius's cousin",
        date: '2026-07-14',
        lat: 0,
        lon: 0,
        confidence: 10,
        zones: [
          PfzZone(
            rank: 1,
            name: 'Ridge & shelf',
            lat: 0,
            lon: 0,
            status: PfzStatus.scoreable,
            pfz: 70,
          ),
        ],
      );

      final gpx = PfzGpxExport.build(res);

      // The whole point: it parses despite the raw metacharacters.
      final doc = XmlDocument.parse(gpx);
      final metadataName = doc
          .findAllElements('metadata')
          .first
          .getElement('name')!
          .innerText;
      expect(metadataName, contains('Swordfish & Sailfish <deep>'));

      final wptName =
          doc.findAllElements('wpt').first.getElement('name')!.innerText;
      expect(wptName, 'Ridge & shelf');
    });

    test('an empty zone list still yields a valid document', () {
      final gpx = PfzGpxExport.build(response(const []));
      final doc = XmlDocument.parse(gpx);
      expect(doc.findAllElements('wpt'), isEmpty);
      // No route, no track: there is nothing to draw, and inventing an empty
      // course for a plotter to follow is worse than omitting it.
      expect(doc.findAllElements('rte'), isEmpty);
      expect(doc.findAllElements('trk'), isEmpty);
    });
  });

  group('unscored zones are never a 0', () {
    test('the waypoint description says not scored, not zero', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, name: 'Needs bottom T', status: PfzStatus.insufficientData),
      ]));

      final doc = XmlDocument.parse(gpx);
      final desc = doc.findAllElements('wpt').first.getElement('desc')!.innerText;
      expect(desc, contains('PFZ not scored'));
      expect(desc, contains('data insufficient'));
      expect(desc, isNot(contains('PFZ 0')));
    });

    test('the score extension carries the literal words, not a number', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, status: PfzStatus.unavailable),
      ]));

      final doc = XmlDocument.parse(gpx);
      final score = doc
          .findAllElements('wpt')
          .first
          .findAllElements('shaka:pfz-score' as String)
          .first
          .innerText;
      expect(score, 'not scored');
    });

    test('an out-of-range species says so in words', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, status: PfzStatus.unavailable),
      ]));
      final desc = XmlDocument
          .parse(gpx)
          .findAllElements('wpt')
          .first
          .getElement('desc')!
          .innerText;
      expect(desc, contains('outside its documented range'));
    });
  });

  group('route and track', () {
    test('the route runs through scored zone centres in rank order', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, name: 'A', lat: 36.40, lon: 18.10, pfz: 90),
        zone(rank: 2, name: 'B', lat: 36.50, lon: 18.20, pfz: 70),
      ]));

      final doc = XmlDocument.parse(gpx);
      final rte = doc.findAllElements('rte').single;
      final points = rte.findAllElements('rtept').toList();
      expect(points, hasLength(2));
      expect(points[0].getAttribute('lat'), '36.400000');
      expect(points[1].getAttribute('lat'), '36.500000');
    });

    test('an unscored zone is kept out of the course', () {
      // It stays as a waypoint — the angler may still want to look at it — but
      // it must not be a steerable waypoint, because we cannot vouch for it.
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, name: 'A', pfz: 90),
        zone(rank: 2, name: 'B', status: PfzStatus.insufficientData),
      ]));

      final doc = XmlDocument.parse(gpx);
      expect(doc.findAllElements('wpt'), hasLength(2));
      final rte = doc.findAllElements('rte').single;
      expect(rte.findAllElements('rtept'), hasLength(1));
    });

    test('a file with nothing scoreable has no route at all', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, status: PfzStatus.unavailable),
      ]));
      final doc = XmlDocument.parse(gpx);
      expect(doc.findAllElements('rte'), isEmpty);
      expect(doc.findAllElements('wpt'), hasLength(1));
    });

    test('the route is labelled as a suggestion, not an instruction', () {
      final gpx = PfzGpxExport.build(response([zone(rank: 1, pfz: 90)]));
      final desc = XmlDocument
          .parse(gpx)
          .findAllElements('rte')
          .single
          .getElement('desc')!
          .innerText;
      expect(desc, contains('not a steering instruction'));
    });

    test('the track says it was never actually sailed', () {
      final gpx = PfzGpxExport.build(response([zone(rank: 1, pfz: 90)]));
      final desc = XmlDocument
          .parse(gpx)
          .findAllElements('trk')
          .single
          .getElement('desc')!
          .innerText;
      expect(desc, contains('no vessel has driven this line'));
    });

    test('polygon vertices are not turned into a course', () {
      final poly = [
        const PfzPoint(lat: 36.40, lon: 18.10),
        const PfzPoint(lat: 36.45, lon: 18.10),
        const PfzPoint(lat: 36.45, lon: 18.15),
        const PfzPoint(lat: 36.40, lon: 18.15),
      ];
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, pfz: 90, polygon: poly),
      ]));

      final doc = XmlDocument.parse(gpx);
      // One track point for the centre, not four for the outline.
      expect(doc.findAllElements('trk').single
          .findAllElements('trkseg')
          .single
          .findAllElements('trkpt'), hasLength(1));
      // The outline is recorded as a vertex count in an extension, so a tool
      // that wants the shape knows it exists.
      final note = doc
          .findAllElements('wpt')
          .single
          .findAllElements('shaka:pfz-polygon-points' as String)
          .single
          .innerText;
      expect(note, '4');
    });
  });

  group('model context travels in the file', () {
    test('mode and size class are exported', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, pfz: 80, mode: 'spawning', sizeClass: 'adult'),
      ]));

      final doc = XmlDocument.parse(gpx);
      final wpt = doc.findAllElements('wpt').single;
      expect(
        wpt.findAllElements('shaka:pfz-mode' as String).single.innerText,
        'spawning',
      );
      expect(
        wpt.findAllElements('shaka:pfz-size-class' as String).single.innerText,
        'adult',
      );
    });

    test('the metadata names the context that produced the numbers', () {
      final gpx = PfzGpxExport.build(response([
        zone(rank: 1, pfz: 80, mode: 'feeding', sizeClass: 'large'),
      ]));
      final desc = XmlDocument
          .parse(gpx)
          .findAllElements('metadata')
          .single
          .getElement('desc')!
          .innerText;
      expect(desc, contains('feeding / large'));
    });
  });

  group('trend track', () {
    PfzZoneHistory history(List<PfzHistoryDay> days) {
      return PfzZoneHistory(
        speciesId: 'bluefin_tuna',
        speciesName: 'Bluefin Tuna',
        speciesScientificName: 'Thunnus thynnus',
        anchorLat: 36.4,
        anchorLon: 18.1,
        mode: 'feeding',
        sizeClass: 'large',
        fromDate: days.isEmpty ? '2026-07-08' : days.first.date,
        toDate: days.isEmpty ? '2026-07-14' : days.last.date,
        days: days,
      );
    }

    test('unscored days are omitted, never plotted as a value', () {
      final gpx = PfzGpxExport.build(
        response([zone(rank: 1, pfz: 80)]),
        history: history(const [
          PfzHistoryDay(date: '2026-07-12', topPfz: 70),
          // No score: habitat refused, or a measurement we do not have.
          PfzHistoryDay(date: '2026-07-13'),
          PfzHistoryDay(date: '2026-07-14', topPfz: 80),
        ]),
      );

      final doc = XmlDocument.parse(gpx);
      final trendTrack = doc
          .findAllElements('trk')
          .firstWhere((t) => t.getElement('name')!.innerText.contains('history'));
      final points = trendTrack
          .findAllElements('trkseg')
          .single
          .findAllElements('trkpt')
          .toList();

      expect(points, hasLength(2),
          reason: 'three recorded days, one of them unscored, means two points');
      for (final p in points) {
        final score = p
            .findAllElements('shaka:pfz' as String)
            .single
            .innerText;
        expect(int.parse(score), greaterThan(0));
      }
      // And the reason for the omission is stated, not just implied.
      final desc = trendTrack.getElement('desc')!.innerText;
      expect(desc, contains('omitted rather than interpolated'));
    });

    test('no trend track when nothing ever scored', () {
      final gpx = PfzGpxExport.build(
        response([zone(rank: 1, pfz: 80)]),
        history: history(const [
          PfzHistoryDay(date: '2026-07-12'),
          PfzHistoryDay(date: '2026-07-13'),
        ]),
      );
      final doc = XmlDocument.parse(gpx);
      expect(doc.findAllElements('trk'), hasLength(1));
    });

    test('a null history changes nothing about the zone export', () {
      final withNull = PfzGpxExport.build(response([zone(rank: 1, pfz: 80)]));
      final doc = XmlDocument.parse(withNull);
      expect(doc.findAllElements('trk'), hasLength(1));
    });
  });

  group('file name', () {
    test('is filesystem-safe and carries the identifying details', () {
      final name = PfzGpxExport.fileName(response([zone(rank: 1, pfz: 80)]));
      expect(name, endsWith('.gpx'));
      expect(name, contains('bluefin_tuna'));
      expect(name, contains('20260714'));
      expect(name, isNot(contains(' ')));
      expect(name, isNot(contains(':')));
    });

    test('a negative latitude does not produce a path separator', () {
      final res = PfzZonesResponse(
        speciesId: 'sp',
        speciesName: 'S',
        speciesScientificName: '',
        date: '2026-07-14',
        lat: -33.9,
        lon: -18.4,
        confidence: 0,
        zones: const [],
      );
      final name = PfzGpxExport.fileName(res);
      expect(name, isNot(contains('/')));
      expect(name, isNot(contains('\\')));
    });
  });
}
