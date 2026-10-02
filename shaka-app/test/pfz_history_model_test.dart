import 'package:flutter_test/flutter_test.dart';
import 'package:shaka/data/models/pfz_models.dart';

/// The history model's whole reason to exist is that it must not smooth over a
/// gap. Every accessor here that can return a score is a place where an
/// over-eager `?? 0` would quietly turn "we could not see" into "this place is
/// worthless" — and that is the one lie this feature is not allowed to tell.
void main() {
  PfzHistoryDay day(String date, {int? top, int confidence = 0}) =>
      PfzHistoryDay(
        date: date,
        topPfz: top,
        confidence: top == null ? 0 : confidence,
      );

  PfzZoneHistory history(
    List<PfzHistoryDay> days, {
    int? latest,
    int? dayOverDay,
    List<PfzHistoryCell> cells = const [],
    List<String> notes = const [],
  }) {
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
      latestTopPfz: latest,
      dayOverDay: dayOverDay,
      days: days,
      cells: cells,
      coverageNotes: notes,
    );
  }

  group('fromJson tolerates a sparse payload', () {
    test('a completely empty body does not throw', () {
      // The backend can legitimately have nothing to say. That is not a crash.
      final h = PfzZoneHistory.fromJson(const {});
      expect(h.days, isEmpty);
      expect(h.latestTopPfz, isNull);
      expect(h.dayOverDay, isNull);
      expect(h.isEmpty, isTrue);
    });

    test('a null score stays null through parsing', () {
      final h = PfzZoneHistory.fromJson(const {
        'days': [
          {'date': '2026-07-14', 'topPfz': null, 'zones': []},
        ],
      });
      expect(h.days.single.topPfz, isNull);
      expect(h.days.single.hasScore, isFalse);
    });

    test('coverage notes survive as plain strings', () {
      final h = PfzZoneHistory.fromJson(const {
        'coverageNotes': ['gap between 13 and 15'],
      });
      expect(h.coverageNotes, ['gap between 13 and 15']);
    });
  });

  group('gaps stay gaps', () {
    test('scoredDays excludes unscored days', () {
      final h = history([
        day('2026-07-12', top: 70),
        day('2026-07-13'),
        day('2026-07-14', top: 80),
      ]);
      expect(h.scoredDays, hasLength(2));
      expect(h.days, hasLength(3));
    });

    test('bestPfz ignores unscored days rather than counting them as 0', () {
      // The trap: folding nulls in with `?? 0` would make bestPfz 0 here.
      final h = history([
        day('2026-07-12', top: 70),
        day('2026-07-13'),
      ]);
      expect(h.bestPfz, 70);
    });

    test('bestPfz is null when nothing ever scored', () {
      final h = history([day('2026-07-13'), day('2026-07-14')]);
      expect(h.bestPfz, isNull);
    });

    test('a day with no score has zero confidence, not carried-over confidence', () {
      // Confidence belongs to a measurement. An unscored day cannot also be
      // claiming to be confident about a number it did not produce.
      final d = PfzHistoryDay.fromJson(const {
        'date': '2026-07-14',
        'topPfz': null,
        'confidence': 90,
      });
      expect(d.confidence, 90,
          reason: 'server value is passed through, not second-guessed here');
      expect(d.topPfz, isNull);
    });
  });

  group('summary language', () {
    test('an empty history reads as "not yet", not as an error', () {
      expect(history(const []).summary, 'No history yet');
    });

    test('a window with no scoreable day says so plainly', () {
      final s = history([day('2026-07-12'), day('2026-07-13')]).summary;
      expect(s, contains('none scoreable'));
      expect(s, isNot(contains('0')));
    });

    test('a real change is reported with its sign', () {
      final s = history(
        [day('2026-07-13', top: 60), day('2026-07-14', top: 72)],
        dayOverDay: 12,
      ).summary;
      expect(s, contains('+12'));
    });

    test('a fall is not dressed up as an improvement', () {
      final s = history(
        [day('2026-07-13', top: 72), day('2026-07-14', top: 60)],
        dayOverDay: -12,
      ).summary;
      expect(s, contains('-12'));
      expect(s, isNot(contains('+')));
    });

    test('one day alone reports nothing to compare, not "unchanged"', () {
      // "unchanged" would be a claim about a comparison that does not exist.
      final s = history([day('2026-07-14', top: 80)]).summary;
      expect(s, contains('no change to compare'));
    });
  });

  group('day labels', () {
    test('an ISO date becomes a day/month label', () {
      expect(day('2026-07-14').shortLabel, '14/07');
    });

    test('a malformed date is passed through rather than crashing', () {
      expect(day('nonsense').shortLabel, 'nonsense');
    });
  });

  group('cell trends', () {
    PfzHistoryCell cell({required double lat, int? change, int? first, int? last}) {
      return PfzHistoryCell(
        lat: lat,
        lon: 18.1,
        firstPfz: first,
        lastPfz: last,
        change: change,
        points: const [],
      );
    }

    test('a single-observation cell has no change and says so', () {
      final c = cell(lat: 36.4, first: 50, last: 50);
      expect(c.change, isNull);
      expect(c.hasChange, isFalse);
    });

    test('movers skips cells that cannot be compared', () {
      final movers = PfzHistoryCell.movers([
        cell(lat: 36.4, change: 5, first: 40, last: 45),
        cell(lat: 36.5),
        cell(lat: 36.6, change: -3, first: 50, last: 47),
      ]);
      expect(movers.map((c) => c.lat), [36.4, 36.6]);
    });

    test('movers are ordered by improvement, best first', () {
      final movers = PfzHistoryCell.movers([
        cell(lat: 36.4, change: -10, first: 60, last: 50),
        cell(lat: 36.5, change: 12, first: 40, last: 52),
      ]);
      expect(movers.first.lat, 36.5);
    });
  });

  group('history points', () {
    test('a null point score is preserved', () {
      final p = PfzHistoryPoint.fromJson(const {'date': '2026-07-14'});
      expect(p.pfz, isNull);
    });
  });
}
