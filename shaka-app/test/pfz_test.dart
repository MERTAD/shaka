import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shaka/data/models/pfz_models.dart';
import 'package:shaka/presentation/widgets/pfz_card.dart';

/// The load-bearing contract of this feature: **a species without a score is
/// never shown as 0**. `unavailable` and `insufficientData` are different
/// claims with different remedies, and a UI that flattens them into a number
/// tells the angler something false. These tests fail if anyone reintroduces a
/// `?? 0` on the pfz field.
void main() {
  group('PfzSpeciesResult.fromJson status/pfz contract', () {
    test('scoreable keeps its score', () {
      final r = PfzSpeciesResult.fromJson({
        'id': 'bluefin_tuna',
        'status': 'scoreable',
        'pfz': 78,
        'confidence': 60,
      });
      expect(r.status, PfzStatus.scoreable);
      expect(r.pfz, 78);
      expect(r.status.hasScore, isTrue);
    });

    test('unavailable yields a null score, never 0', () {
      final r = PfzSpeciesResult.fromJson({
        'id': 'common_octopus',
        'status': 'unavailable',
        'pfz': null,
        'blockers': ['hard bottom substrate required'],
      });
      expect(r.status, PfzStatus.unavailable);
      expect(r.pfz, isNull, reason: 'unavailable must not be coerced to 0');
      expect(r.status.hasScore, isFalse);
      expect(r.blockers, isNotEmpty);
    });

    test('insufficientData yields a null score, never 0', () {
      final r = PfzSpeciesResult.fromJson({
        'id': 'sardine',
        'status': 'insufficient_data',
        'pfz': null,
        'missingFactors': ['sst'],
      });
      expect(r.status, PfzStatus.insufficientData);
      expect(r.pfz, isNull, reason: 'insufficient data must not be coerced to 0');
    });

    test('a missing pfz key on a scoreable species stays null, not 0', () {
      // Defensive: the backend should never send this, but if it does we must
      // not invent a 0/100 "worst possible" verdict out of a field absence.
      final r = PfzSpeciesResult.fromJson({
        'id': 'anchovy',
        'status': 'scoreable',
      });
      expect(r.pfz, isNull);
    });

    test('an unrecognised status degrades to insufficientData, not a score', () {
      final r = PfzSpeciesResult.fromJson({
        'id': 'bogue',
        'status': 'something_new_from_backend',
        'pfz': 91,
      });
      expect(r.status, PfzStatus.insufficientData);
      expect(r.status.hasScore, isFalse,
          reason: 'unknown status must fail closed, never surface a score');
    });
  });

  group('PfzResponse partitions', () {
    PfzResponse build() => PfzResponse.fromJson({
          'spotId': 'spot-1',
          'date': '2026-09-28',
          'confidence': 55,
          'species': [
            {'id': 'bluefin_tuna', 'status': 'scoreable', 'pfz': 80},
            {'id': 'anchovy', 'status': 'scoreable', 'pfz': 62},
            {'id': 'sardine', 'status': 'insufficient_data', 'pfz': null},
            {'id': 'common_sole', 'status': 'unavailable', 'pfz': null},
          ],
          'missingFactors': ['swell'],
        });

    test('splits scoreable / insufficient / unavailable', () {
      final r = build();
      expect(r.scoreable.map((s) => s.id), ['bluefin_tuna', 'anchovy']);
      expect(r.insufficient.single.id, 'sardine');
      expect(r.unavailable.single.id, 'common_sole');
      expect(r.best!.id, 'bluefin_tuna');
    });

    test('best is null when nothing is scoreable', () {
      final r = PfzResponse.fromJson({
        'spotId': 'spot-1',
        'date': '2026-09-28',
        'confidence': 0,
        'species': [
          {'id': 'sardine', 'status': 'insufficient_data', 'pfz': null},
        ],
      });
      expect(r.best, isNull);
    });
  });

  group('PfzFactorScore', () {
    test('parses the response-level confidence enum', () {
      expect(PfzFactorConfidence.parse('cited'), PfzFactorConfidence.cited);
      expect(PfzFactorConfidence.parse('expert'), PfzFactorConfidence.expert);
      expect(PfzFactorConfidence.parse('unknown'), PfzFactorConfidence.unknown);
      expect(PfzFactorConfidence.parse(null), PfzFactorConfidence.unknown);
      expect(PfzFactorConfidence.parse('brand_new'), PfzFactorConfidence.unknown);
    });

    test('has a human label for the new chlorophyll-front factor', () {
      final f = PfzFactorScore.fromJson({
        'factor': 'chla_gradient',
        'score': 50,
        'weight': 0.15,
        'confidence': 'expert',
      });
      expect(f.factor, 'chla_gradient');
      expect(f.confidence, PfzFactorConfidence.expert);
      expect(f.label, 'Chlorophyll front');
    });
  });

  group('PfzCard rendering', () {
    Future<void> pump(WidgetTester tester, PfzResponse? pfz,
            {bool loading = false, String? error}) =>
        tester.pumpWidget(MaterialApp(
          home: Scaffold(body: PfzCard(pfz: pfz, loading: loading, error: error)),
        ));

    testWidgets('shows an unavailable species by name and no 0 score',
        (tester) async {
      await pump(
        tester,
        PfzResponse.fromJson({
          'spotId': 'spot-1',
          'date': '2026-09-28',
          'confidence': 40,
          'species': [
            {
              'id': 'common_sole',
              'commonName': 'Common sole',
              'status': 'unavailable',
              'pfz': null,
            },
          ],
        }),
      );
      expect(find.textContaining('outside habitat'), findsOneWidget);
      expect(find.textContaining('Common sole'), findsWidgets);
      expect(find.text('0'), findsNothing,
          reason: 'an unavailable species must never render as 0');
    });

    testWidgets('surfaces unscorable requirements (the blind-spot caveat)',
        (tester) async {
      await pump(
        tester,
        PfzResponse.fromJson({
          'spotId': 'spot-1',
          'date': '2026-09-28',
          'confidence': 70,
          'species': [
            {
              'id': 'common_octopus',
              'commonName': 'Common octopus',
              'status': 'scoreable',
              'pfz': 71,
              'confidence': 65,
              'unscorableRequirements': [
                'hard bottom substrate required - no substrate source'
              ],
            },
          ],
        }),
      );
      expect(find.textContaining('hard bottom substrate'), findsOneWidget);
    });

    testWidgets('renders nothing when the spot is unknown (404)', (tester) async {
      await pump(tester, null);
      expect(find.byType(PfzCard), findsOneWidget);
      expect(find.text('Species potential'), findsNothing);
    });

    testWidgets('shows the error text rather than an empty card',
        (tester) async {
      await pump(tester, null, error: 'Could not reach the scoring service.');
      expect(find.textContaining('Could not reach'), findsOneWidget);
    });

    testWidgets('flags an unbanded chlorophyll front as low confidence',
        (tester) async {
      await pump(
        tester,
        PfzResponse.fromJson({
          'spotId': 'spot-1',
          'date': '2026-09-28',
          'confidence': 50,
          'species': [
            {
              'id': 'bluefin_tuna',
              'commonName': 'Bluefin tuna',
              'status': 'scoreable',
              'pfz': 66,
              'confidence': 50,
              'drivers': ['sst', 'chla_gradient'],
              'lowConfidenceFactors': ['chla_gradient'],
            },
          ],
        }),
      );
      expect(find.textContaining('expert thresholds'), findsOneWidget);
      expect(find.textContaining('chlorophyll front'), findsWidgets);
    });
  });
}
