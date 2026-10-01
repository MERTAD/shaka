import 'package:flutter/material.dart';
import '../../core/theme/app_colors.dart';
import '../../data/models/pfz_models.dart';

/// Species-specific Potential Fishing Zone card for the spot detail screen.
///
/// The one rule this widget enforces: **a species that has no score is never
/// shown as 0.** It is shown as `unavailable` or "not enough data", with the
/// reason. The generic Shaka score answers "how good are the conditions";
/// this card answers "for which species, and how sure are we", and those are
/// different questions with very different failure modes.
class PfzCard extends StatelessWidget {
  final PfzResponse? pfz;
  final bool loading;
  final String? error;

  const PfzCard({
    super.key,
    this.pfz,
    this.loading = false,
    this.error,
  });

  @override
  Widget build(BuildContext context) {
    if (loading && pfz == null) {
      return const _Shell(child: _Loading());
    }
    if (error != null && pfz == null) {
      return _Shell(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.cloud_off,
                size: 16, color: AppColors.darkTextMuted),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Species scoring unavailable. $error',
                style: const TextStyle(
                    color: AppColors.darkTextMuted, fontSize: 12, height: 1.4),
              ),
            ),
          ],
        ),
      );
    }
    if (pfz == null) return const SizedBox.shrink();

    final data = pfz!;
    final scoreable = data.scoreable;
    final best = data.best;

    return _Shell(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.set_meal,
                  size: 16, color: AppColors.oceanBlueLight),
              const SizedBox(width: 8),
              const Text(
                'Species potential',
                style: TextStyle(
                    color: AppColors.darkTextPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w600),
              ),
              const Spacer(),
              if (data.confidence > 0)
                _ConfidenceChip(confidence: data.confidence),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            scoreable.isEmpty
                ? 'No species could be scored for this spot on ${data.date}.'
                : '${scoreable.length} of ${data.species.length} species scored'
                    ' · best: ${best!.commonName} ${best.pfz}/100',
            style: const TextStyle(
                color: AppColors.darkTextMuted, fontSize: 12),
          ),
          if (scoreable.isNotEmpty) ...[
            const SizedBox(height: 12),
            for (final s in scoreable.take(8)) _SpeciesRow(result: s),
          ],
          if (data.insufficient.isNotEmpty) ...[
            const SizedBox(height: 10),
            _SectionNote(
              icon: Icons.help_outline,
              color: AppColors.warning,
              title: '${data.insufficient.length} species not enough data',
              // A count alone is not an answer: the user cannot tell whether the
              // app had nothing, or had everything except the one measurement
              // this species is written against. Name the species, the model it
              // would have been scored under, and the measurement that is
              // missing, so "no score" reads as a gap rather than a verdict.
              detail: 'Habitat looks plausible but we lack the measurements '
                  'to say. Not a zero score.',
              children: [
                for (final s in data.insufficient) _RefusedRow(result: s),
              ],
            ),
          ],
          if (data.unavailable.isNotEmpty) ...[
            const SizedBox(height: 10),
            _SectionNote(
              icon: Icons.do_not_disturb_on_outlined,
              color: AppColors.darkTextMuted,
              title: '${data.unavailable.length} species outside habitat here',
              detail: data.unavailable
                  .map((s) => s.commonName)
                  .take(6)
                  .join(', '),
            ),
          ],
          if (data.missingFactors.isNotEmpty) ...[
            const SizedBox(height: 10),
            _SectionNote(
              icon: Icons.sensors_off,
              color: AppColors.coral,
              title: 'Data gaps affecting every species',
              detail: data.missingFactors
                  .map(_factorLabel)
                  .join(', '),
            ),
          ],
          if (data.coverageNotes.isNotEmpty) ...[
            const SizedBox(height: 12),
            const Divider(color: AppColors.darkBorder, height: 1),
            const SizedBox(height: 8),
            for (final note in data.coverageNotes)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.info_outline,
                        size: 13, color: AppColors.darkTextHint),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        note,
                        style: const TextStyle(
                            color: AppColors.darkTextHint,
                            fontSize: 11,
                            height: 1.4),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ],
      ),
    );
  }
}

class _SpeciesRow extends StatelessWidget {
  final PfzSpeciesResult result;
  const _SpeciesRow({required this.result});

  /// e.g. "feeding · large fish · central med" — only the parts the backend
  /// actually applied, so the row never implies a mode it was not scored under.
  String? get _contextLabel => _contextLabelOf(result);

  @override
  Widget build(BuildContext context) {
    final pfz = result.pfz;
    final color = pfz == null ? AppColors.darkTextMuted : _scoreColor(pfz);
    final weak = result.confidence < 50;

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      result.commonName,
                      style: const TextStyle(
                          color: AppColors.darkTextPrimary,
                          fontSize: 13,
                          fontWeight: FontWeight.w500),
                    ),
                    Text(
                      result.scientificName,
                      style: const TextStyle(
                          color: AppColors.darkTextHint, fontSize: 10),
                    ),
                    // The model that produced the number. Bluefin feeding and
                    // spawning are near-inverted, so "72/100" is meaningless
                    // without knowing which one the user is looking at — and the
                    // size class is the difference between a band that was
                    // fitted and one that was not.
                    if (_contextLabel != null)
                      Text(
                        _contextLabel!,
                        style: const TextStyle(
                            color: AppColors.darkTextMuted,
                            fontSize: 10,
                            fontStyle: FontStyle.italic),
                      ),
                  ],
                ),
              ),
              if (pfz != null) ...[
                Text(
                  '$pfz',
                  style: TextStyle(
                      color: color, fontSize: 18, fontWeight: FontWeight.w700),
                ),
                const Text(
                  '/100',
                  style: TextStyle(
                      color: AppColors.darkTextHint, fontSize: 10),
                ),
              ],
            ],
          ),
          if (pfz != null) ...[
            const SizedBox(height: 4),
            ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: LinearProgressIndicator(
                value: pfz / 100,
                minHeight: 4,
                backgroundColor: AppColors.darkBorder,
                valueColor: AlwaysStoppedAnimation(color),
              ),
            ),
          ],
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 4,
            children: [
              if (result.factors.isNotEmpty)
                _Pill(
                  label: result.drivers.isEmpty
                      ? 'all factors weak'
                      : 'driven by ${result.drivers.map(_factorLabel).join(', ')}',
                  color: AppColors.oceanBlueLight,
                ),
              if (weak)
                _Pill(
                  label: 'low confidence ${result.confidence}%',
                  color: AppColors.warning,
                ),
              if (result.lowConfidenceFactors.isNotEmpty)
                _Pill(
                  label:
                      'expert thresholds: ${result.lowConfidenceFactors.map(_factorLabel).join(', ')}',
                  color: AppColors.darkTextMuted,
                ),
              if (result.missingFactors.isNotEmpty)
                _Pill(
                  label: 'unmeasured: ${result.missingFactors.map(_factorLabel).join(', ')}',
                  color: AppColors.coral,
                ),
            ],
          ),
          // A high score that ignores a documented requirement is a trap.
          // Say so on the row, not just in a global footnote.
          for (final req in result.unscorableRequirements)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.blind,
                      size: 12, color: AppColors.coralLight),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      req,
                      style: const TextStyle(
                          color: AppColors.coralLight,
                          fontSize: 10,
                          height: 1.35),
                    ),
                  ),
                ],
              ),
            ),
          for (final b in result.blockers)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.block,
                      size: 12, color: AppColors.darkTextMuted),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      b,
                      style: const TextStyle(
                          color: AppColors.darkTextMuted,
                          fontSize: 10,
                          height: 1.35),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _ConfidenceChip extends StatelessWidget {
  final int confidence;
  const _ConfidenceChip({required this.confidence});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: AppColors.darkBorder,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.verified_outlined,
              size: 11,
              color: confidence >= 70
                  ? AppColors.success
                  : confidence >= 40
                      ? AppColors.warning
                      : AppColors.coral),
          const SizedBox(width: 4),
          Text(
            '$confidence% sure',
            style: const TextStyle(color: AppColors.darkTextMuted, fontSize: 10),
          ),
        ],
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  final String label;
  final Color color;
  const _Pill({required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(color: color, fontSize: 9.5, height: 1.3),
      ),
    );
  }
}

class _SectionNote extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String title;
  final String detail;
  final List<Widget> children;
  const _SectionNote({
    required this.icon,
    required this.color,
    required this.title,
    required this.detail,
    this.children = const [],
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 13, color: color),
        const SizedBox(width: 6),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title,
                  style: TextStyle(
                      color: color, fontSize: 11.5, fontWeight: FontWeight.w500)),
              Text(detail,
                  style: const TextStyle(
                      color: AppColors.darkTextHint, fontSize: 10.5, height: 1.35)),
              if (children.isNotEmpty) ...[
                const SizedBox(height: 8),
                ...children,
              ],
            ],
          ),
        ),
      ],
    );
  }
}

/// A species that got no number, named with the model it would have been scored
/// under and the measurement that stopped it.
///
/// The mode and size class are the point: "bluefin, feeding, large fish" and
/// "bluefin, spawning" are different models, and a user refused one deserves to
/// know which one refused them rather than being told only that a score is
/// unavailable.
class _RefusedRow extends StatelessWidget {
  final PfzSpeciesResult result;
  const _RefusedRow({required this.result});

  @override
  Widget build(BuildContext context) {
    final ctx = _contextLabelOf(result);
    // Blockers arrive as full sentences, which is too long for a summary line,
    // so take the measurement out of the first one: the engine words them
    // "<factor> is required to score ...".
    final reason = result.blockers.isEmpty
        ? null
        : result.blockers.first.split(' is required ').first;
    final extra = result.blockers.length - (reason != null ? 1 : 0);

    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            [
              result.commonName,
              if (ctx != null) ctx,
            ].join(' · '),
            style: const TextStyle(
                color: AppColors.darkTextMuted,
                fontSize: 10.5,
                fontWeight: FontWeight.w500),
          ),
          if (reason != null)
            Text(
              extra > 0
                  ? 'missing: $reason (+$extra more)'
                  : 'missing: $reason',
              style: const TextStyle(
                  color: AppColors.darkTextHint,
                  fontSize: 10,
                  height: 1.3),
            ),
          if (result.note != null)
            Text(
              result.note!,
              style: const TextStyle(
                  color: AppColors.darkTextHint,
                  fontSize: 10,
                  fontStyle: FontStyle.italic,
                  height: 1.3),
            ),
        ],
      ),
    );
  }
}

/// Shared by the scored and refused rows so both describe the same model the
/// same way: e.g. "feeding · large fish · central med".
String? _contextLabelOf(PfzSpeciesResult result) {
  final parts = <String>[
    if (result.mode != null) result.mode!.replaceAll('_', ' '),
    if (result.sizeClass != null) '${result.sizeClass} fish',
    if (result.region != null) result.region!.replaceAll('_', ' '),
  ];
  return parts.isEmpty ? null : parts.join(' · ');
}

class _Shell extends StatelessWidget {
  final Widget child;
  const _Shell({required this.child});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: child,
    );
  }
}

class _Loading extends StatelessWidget {
  const _Loading();

  @override
  Widget build(BuildContext context) {
    return const Row(
      children: [
        SizedBox(
            width: 12,
            height: 12,
            child: CircularProgressIndicator(strokeWidth: 2)),
        SizedBox(width: 10),
        Text('Scoring species…',
            style: TextStyle(color: AppColors.darkTextMuted, fontSize: 12)),
      ],
    );
  }
}

Color _scoreColor(int pfz) {
  if (pfz >= 80) return AppColors.scoreExcellent;
  if (pfz >= 60) return AppColors.scoreGood;
  if (pfz >= 40) return AppColors.scoreAverage;
  if (pfz >= 20) return AppColors.scoreBelowAvg;
  return AppColors.scorePoor;
}

String _factorLabel(String key) => switch (key) {
      'sst' => 'temperature',
      'chl' => 'chlorophyll',
      'chla_gradient' => 'chlorophyll front',
      'depth' => 'depth',
      'wind' => 'wind',
      'swell' => 'swell',
      'current' => 'current',
      'solunar' => 'moon/tide',
      'season' => 'season',
      _ => key,
    };
