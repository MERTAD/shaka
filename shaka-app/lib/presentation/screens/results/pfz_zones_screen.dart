import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:maplibre_gl/maplibre_gl.dart';
import 'package:path_provider/path_provider.dart';
import '../../../core/theme/app_colors.dart';
import '../../../data/api/shaka_api_client.dart';
import '../../../data/models/pfz_models.dart';
import '../../../data/services/pfz_gpx_export.dart';

/// Offshore SatCatch-style targets: rank zones around a lat/lon and draw each
/// as its data-shaped polygon with a named centre ("BluefinTuna 28/09 001").
class PfzZonesScreen extends StatefulWidget {
  final double lat;
  final double lon;
  final String date;

  const PfzZonesScreen({
    super.key,
    required this.lat,
    required this.lon,
    required this.date,
  });

  @override
  State<PfzZonesScreen> createState() => _PfzZonesScreenState();
}

class PfzSpecies {
  final String id;
  final String label;
  final String scientificName;
  final String category;

  const PfzSpecies({
    required this.id,
    required this.label,
    required this.scientificName,
    required this.category,
  });
}

const List<PfzSpecies> _speciesCatalog = [
  PfzSpecies(
    id: 'bluefin_tuna',
    label: 'Atlantic Bluefin Tuna',
    scientificName: 'Thunnus thynnus',
    category: 'Large pelagic',
  ),
  PfzSpecies(
    id: 'little_tunny',
    label: 'Little Tunny',
    scientificName: 'Euthynnus alletteratus',
    category: 'Large pelagic',
  ),
  PfzSpecies(
    id: 'swordfish',
    label: 'Swordfish',
    scientificName: 'Xiphias gladius',
    category: 'Large pelagic',
  ),
  PfzSpecies(
    id: 'sardine',
    label: 'European Sardine',
    scientificName: 'Sardina pilchardus',
    category: 'Small pelagic',
  ),
  PfzSpecies(
    id: 'anchovy',
    label: 'European Anchovy',
    scientificName: 'Engraulis encrasicolus',
    category: 'Small pelagic',
  ),
  PfzSpecies(
    id: 'horse_mackerel',
    label: 'Horse Mackerel',
    scientificName: 'Trachurus trachurus',
    category: 'Small pelagic',
  ),
  PfzSpecies(
    id: 'scomber',
    label: 'Chub Mackerel',
    scientificName: 'Scomber colias',
    category: 'Small pelagic',
  ),
  PfzSpecies(
    id: 'european_hake',
    label: 'European Hake',
    scientificName: 'Merluccius merluccius',
    category: 'Demersal',
  ),
];

const String _speciesRegion = 'MED & ATLANTIC';

class _PfzZonesScreenState extends State<PfzZonesScreen> {
  final ShakaApiClient _api = ShakaApiClient();
  MapLibreMapController? _mapController;

  bool _loading = true;
  String? _error;
  PfzZonesResponse? _response;
  String _speciesId = 'bluefin_tuna';
  bool _styleReady = false;

  /// Persisted day-over-day history for the current species. Loaded alongside
  /// the zones, and treated as independent: a failure to read history must not
  /// blank the map, and an empty history must not look like a map failure.
  PfzZoneHistory? _history;
  bool _historyLoading = false;
  String? _historyError;

  /// Days of history requested. Kept small by default: the point is "is this
  /// ground improving", which a week answers, and a year of daily cells on a
  /// phone is a scroll nobody reads.
  int _historyDays = 7;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final res = await _api.getZones(
        lat: widget.lat,
        lon: widget.lon,
        date: widget.date,
        speciesId: _speciesId,
      );
      if (!mounted) return;
      setState(() {
        _response = res;
        _loading = false;
      });
      if (_styleReady) await _addZonesLayer();
      _loadHistory();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _loadHistory() async {
    setState(() {
      _historyLoading = true;
      _historyError = null;
    });
    try {
      final history = await _api.getZoneHistory(
        lat: widget.lat,
        lon: widget.lon,
        speciesId: _speciesId,
        days: _historyDays,
      );
      if (!mounted) return;
      setState(() {
        _history = history;
        _historyLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _historyError = e.toString();
        _historyLoading = false;
      });
    }
  }

  void _onMapCreated(MapLibreMapController controller) {
    _mapController = controller;
  }

  Future<void> _onStyleLoaded() async {
    _styleReady = true;
    await _addZonesLayer();
  }

  Future<void> _addZonesLayer() async {
    final c = _mapController;
    final res = _response;
    if (c == null || res == null) return;

    for (final id in const [
      'pfz-zones-fill',
      'pfz-zones-outline',
      'pfz-zones-labels',
    ]) {
      try {
        await c.removeLayer(id);
      } catch (_) {}
    }
    try {
      await c.removeSource('pfz-zones-source');
    } catch (_) {}

    final drawable = res.drawable;
    if (drawable.isEmpty) return;

    final features = drawable.map((zone) {
      final fill = _fillColorHex(zone);
      return {
        'type': 'Feature',
        'properties': {
          'name': zone.name,
          'rank': zone.rank,
          'fill': fill,
        },
        'geometry': {
          'type': 'Polygon',
          'coordinates': [
            zone.polygon.map((p) => p.asLngLat).toList(),
            for (final hole in zone.holes) hole.map((p) => p.asLngLat).toList(),
          ],
        },
      };
    }).toList();

    try {
      await c.addSource(
        'pfz-zones-source',
        GeojsonSourceProperties(data: {
          'type': 'FeatureCollection',
          'features': features,
        }),
      );
      await c.addFillLayer(
        'pfz-zones-source',
        'pfz-zones-fill',
        FillLayerProperties(
          fillColor: ['get', 'fill'],
          fillOpacity: 0.35,
          fillAntialias: true,
        ),
      );
      await c.addLineLayer(
        'pfz-zones-source',
        'pfz-zones-outline',
        LineLayerProperties(
          lineColor: '#1f2937',
          lineWidth: 1.5,
        ),
      );
      await c.addSymbolLayer(
        'pfz-zones-source',
        'pfz-zones-labels',
        const SymbolLayerProperties(
          textField: ['get', 'name'],
          textSize: 11,
          textColor: '#ffffff',
          textHaloColor: '#111827',
          textHaloWidth: 1.2,
          textAllowOverlap: true,
          textIgnorePlacement: true,
          iconAllowOverlap: true,
        ),
      );
    } catch (e) {
      debugPrint('Failed to add PFZ zones layer: $e');
    }
  }

  String _fillColorHex(PfzZone zone) {
    if (!zone.hasScore) return '#4B5563';
    return switch (AppColors.getScoreTier(zone.pfz ?? 0)) {
      5 => _hex(AppColors.scoreExcellent),
      4 => _hex(AppColors.scoreGood),
      3 => _hex(AppColors.scoreAverage),
      2 => _hex(AppColors.scoreBelowAvg),
      _ => _hex(AppColors.scorePoor),
    };
  }

  String _hex(Color color) {
    final argb = color.toARGB32();
    return '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0').toUpperCase()}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        leading: Padding(
          padding: const EdgeInsets.only(left: 12),
          child: TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(
              'Back',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: AppColors.textSecondary,
                  ),
            ),
          ),
        ),
        leadingWidth: 80,
        title: Column(
          children: [
            Text(
              'Fishing targets',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            Text(
              widget.date,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: AppColors.textMuted,
                  ),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'Export track (GPX)',
            onPressed: _loading || (_response?.zones.isEmpty ?? true) ? null : _exportGpx,
            icon: const Icon(Icons.download_outlined),
          ),
          IconButton(
            tooltip: 'Refresh',
            onPressed: _loading ? null : _load,
            icon: const Icon(Icons.refresh),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        children: [
          if (_response != null) _speciesBar(),
          if (_loading)
            const Expanded(
              child: Center(child: CircularProgressIndicator()),
            )
          else if (_error != null)
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(
                    'Could not load zones:\n$_error',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: AppColors.textMuted,
                        ),
                  ),
                ),
              ),
            )
          else ...[
            Expanded(child: Stack(children: [_map(), _legend(), _summaryPanel()])),
            _trendPanel(),
          ],
        ],
      ),
    );
  }

  // ------------------------------------------------------------------ GPX

  /// Write the full track to a file and tell the user where it went.
  ///
  /// No share sheet: this is a file for a chartplotter, and on a phone the useful
  /// thing is the path, not a stream of social apps that cannot open GPX. The
  /// path is shown in a dialog with the honest summary attached, because a file
  /// that silently lands in a downloads folder nobody opens is not a delivery.
  Future<void> _exportGpx() async {
    final response = _response;
    if (response == null || response.zones.isEmpty) return;

    final history = _history;
    final gpx = PfzGpxExport.build(response, history: history);
    final name = PfzGpxExport.fileName(response);
    String? path;
    Object? failure;

    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}${Platform.pathSeparator}$name');
      await file.writeAsString(gpx, flush: true);
      path = file.path;
    } catch (e) {
      failure = e;
    }

    if (!mounted) return;
    if (failure != null) {
      HapticFeedback.heavyImpact();
      _showDialog(
        title: 'Export failed',
        body: 'Could not write the GPX file.\n\n$failure',
        icon: Icons.error_outline,
        iconColor: AppColors.scorePoor,
      );
      return;
    }

    HapticFeedback.mediumImpact();
    final scored = response.zones.where((z) => z.hasScore).length;
    final unscored = response.zones.length - scored;
    _showDialog(
      title: 'Track saved',
      body: [
        '$path',
        '',
        '${response.zones.length} zone waypoint(s), $scored scoreable.'
            '${unscored > 0 ? ' $unscored unscored, exported with their reason rather than a score of 0.' : ''}',
        if (history != null && !history.days.isEmpty)
          'Trend track included: '
              '${history.days.where((d) => d.hasScore).length} of '
              '${history.days.length} recorded day(s) were scoreable; '
              'unscored days were left out, not interpolated.',
        '',
        'Zone polygons are not in the file as a course. A polygon outline is '
            'not a line a boat can follow, so only zone centres are exported.',
      ].join('\n'),
      icon: Icons.check_circle_outline,
      iconColor: AppColors.scoreExcellent,
    );
  }

  void _showDialog({
    required String title,
    required String body,
    required IconData icon,
    required Color iconColor,
  }) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.surface,
        title: Row(
          children: [
            Icon(icon, color: iconColor, size: 20),
            const SizedBox(width: 8),
            Expanded(
              child: Text(title, style: Theme.of(ctx).textTheme.titleSmall),
            ),
          ],
        ),
        content: SingleChildScrollView(
          child: Text(
            body,
            style: Theme.of(ctx).textTheme.bodySmall?.copyWith(
                  color: AppColors.textSecondary,
                ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: Text(
              'Close',
              style: Theme.of(ctx).textTheme.bodyMedium?.copyWith(
                    color: AppColors.textSecondary,
                  ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _speciesBar() {
    final res = _response!;
    final current = _speciesCatalog.firstWhere(
      (s) => s.id == _speciesId,
      orElse: () => _speciesCatalog.first,
    );
    return Container(
      color: AppColors.surface,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Expanded(
            child: Material(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(10),
              child: InkWell(
                borderRadius: BorderRadius.circular(10),
                onTap: _openSpeciesPicker,
                child: Padding(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  child: Row(
                    children: [
                      Icon(
                        Icons.travel_explore,
                        size: 18,
                        color: AppColors.textSecondary,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          current.label,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                      Icon(
                        Icons.keyboard_arrow_down,
                        size: 18,
                        color: AppColors.textMuted,
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              '${res.drawable.length} targets',
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: AppColors.textSecondary,
                  ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _openSpeciesPicker() async {
    final picked = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: AppColors.surface,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) => _SpeciesPickerSheet(
        catalog: _speciesCatalog,
        selectedId: _speciesId,
        region: _speciesRegion,
      ),
    );
    if (picked != null && picked != _speciesId) {
      HapticFeedback.lightImpact();
      setState(() => _speciesId = picked);
      _load();
    }
  }

  Widget _map() {
    return MapLibreMap(
      onMapCreated: _onMapCreated,
      onStyleLoadedCallback: _onStyleLoaded,
      initialCameraPosition: CameraPosition(
        target: LatLng(widget.lat, widget.lon),
        zoom: 9.5,
      ),
      styleString: 'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json',
      compassEnabled: false,
    );
  }

  Widget _legend() {
    return Positioned(
      top: 12,
      right: 12,
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: AppColors.surface.withOpacity(0.95),
          borderRadius: BorderRadius.circular(8),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.08),
              blurRadius: 8,
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: const [
            _LegendItem(color: AppColors.scoreExcellent, label: '80+'),
            SizedBox(height: 4),
            _LegendItem(color: AppColors.scoreGood, label: '60+'),
            SizedBox(height: 4),
            _LegendItem(color: AppColors.scoreAverage, label: '40+'),
            SizedBox(height: 4),
            _LegendItem(color: AppColors.scoreBelowAvg, label: '20+'),
            SizedBox(height: 4),
            _LegendItem(color: AppColors.scorePoor, label: '<20'),
            SizedBox(height: 4),
            _LegendItem(color: Color(0xFF4B5563), label: 'no score'),
          ],
        ),
      ),
    );
  }

  Widget _summaryPanel() {
    final res = _response!;
    final zones = res.zones;
    if (zones.isEmpty) {
      return Positioned(
        left: 12,
        right: 12,
        bottom: 12,
        child: _panel(
          child: Text(
            res.coverageNotes.isNotEmpty
                ? res.coverageNotes.join('\n')
                : 'No zones could be ranked for this location today.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: AppColors.textMuted,
                ),
          ),
        ),
      );
    }
    final top = zones.first;
    return Positioned(
      left: 12,
      right: 12,
      bottom: 12,
      child: _panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              top.name,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 2),
            Text(
              '${res.speciesName} · ${res.date}',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: AppColors.textMuted,
                  ),
            ),
            const SizedBox(height: 8),
            if (top.hasScore)
              Text(
                'PFZ ${top.pfz} · confidence ${top.confidence}',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: AppColors.textSecondary,
                    ),
              )
            else if (top.blockers.isNotEmpty)
              Text(
                top.blockers.join('; '),
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: AppColors.scorePoor,
                    ),
              ),
            const SizedBox(height: 6),
            Text(
              '${zones.length} targets at ${res.lat.toStringAsFixed(2)}, ${res.lon.toStringAsFixed(2)}',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: AppColors.textMuted,
                  ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _panel({required Widget child}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.1),
            blurRadius: 20,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: child,
    );
  }

  // ----------------------------------------------------------------- trend

  /// Day-over-day history strip below the map.
  ///
  /// It draws only what was measured. A day with no scoreable zone is a hole in
  /// the sparkline, not a point at zero: a line drawn through a gap would be a
  /// measurement the model never made, and a marker at 0 would claim the ground
  /// is bad when the truth is that we could not see it.
  Widget _trendPanel() {
    if (_historyLoading && _history == null) {
      return const SizedBox(
        height: 4,
        child: LinearProgressIndicator(minHeight: 2),
      );
    }

    final history = _history;
    if (history == null) {
      if (_historyError != null) {
        return _trendShell(
          child: Text(
            'History unavailable.',
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: AppColors.textMuted,
                ),
          ),
        );
      }
      return const SizedBox.shrink();
    }

    return _trendShell(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Text(
                'Recent days',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: AppColors.textMuted,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.8,
                    ),
              ),
              const Spacer(),
              _historyDaysToggle(),
            ],
          ),
          const SizedBox(height: 8),
          _sparkline(history),
          const SizedBox(height: 6),
          Text(
            history.summary,
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: AppColors.textMuted,
                ),
          ),
          if (history.cells.any((c) => c.hasChange)) ...[
            const SizedBox(height: 6),
            _cellMovers(history),
          ],
          if (history.coverageNotes.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              history.coverageNotes.join(' '),
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: AppColors.textMuted,
                    fontSize: 10,
                  ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _trendShell({required Widget child}) {
    return Container(
      width: double.infinity,
      color: AppColors.surface,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
      child: child,
    );
  }

  Widget _historyDaysToggle() {
    const options = [7, 14, 30];
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final option in options)
          Padding(
            padding: const EdgeInsets.only(left: 4),
            child: InkWell(
              onTap: () {
                if (_historyDays == option) return;
                HapticFeedback.selectionClick();
                setState(() => _historyDays = option);
                _loadHistory();
              },
              borderRadius: BorderRadius.circular(6),
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                decoration: BoxDecoration(
                  color: _historyDays == option
                      ? AppColors.background
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  '${option}d',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: _historyDays == option
                            ? AppColors.textSecondary
                            : AppColors.textMuted,
                      ),
                ),
              ),
            ),
          ),
      ],
    );
  }

  /// A score-per-day chart with real gaps.
  ///
  /// Implemented by hand rather than with a chart package: the whole point is the
  /// gap, and a stock line chart will happily connect across a null unless told
  /// not to in a way that varies by library. Here a gap is drawn as a gap.
  Widget _sparkline(PfzZoneHistory history) {
    final days = history.days;
    if (days.isEmpty) {
      return Text(
        'No days recorded for this box yet. History is written by a daily job, '
        'so a new species or location fills in on later days.',
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: AppColors.textMuted,
            ),
      );
    }

    // A Row of fixed-width cells, one per day. Sized per cell rather than
    // absolutely positioned so the axis labels cannot collide on a narrow phone,
    // and so a 30-day window degrades to "crowded" rather than overlapping text.
    const chartHeight = 46.0;
    const maxDays = 10;
    final stride = days.length > maxDays
        ? (days.length / maxDays).ceil()
        : 1;

    return SizedBox(
      height: chartHeight,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // The plot area. Fixed height, so the guide lines and the day stems can
          // both be absolutely positioned inside it without a layout ambiguity.
          SizedBox(
            height: chartHeight - 12,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                // The 60 and 80 guides are the same thresholds the map legend
                // uses, so "up" in this chart means the same thing as the
                // polygon colour the angler just looked at.
                for (final guide in const [60.0, 80.0])
                  Positioned(
                    left: 0,
                    right: 0,
                    top: _scoreY(guide),
                    child: Container(height: 1, color: AppColors.border),
                  ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (var i = 0; i < days.length; i++)
                      Expanded(child: _dayMark(days[i])),
                  ],
                ),
              ],
            ),
          ),
          // The axis. Only every [stride]-th day is labelled on a long window,
          // because a date you cannot read is noise, not information.
          SizedBox(
            height: 12,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < days.length; i++)
                  Expanded(
                    child: Center(
                      child: (i % stride == 0 || i == days.length - 1)
                          ? Text(
                              days[i].shortLabel,
                              maxLines: 1,
                              overflow: TextOverflow.clip,
                              style: Theme.of(context)
                                  .textTheme
                                  .labelSmall
                                  ?.copyWith(
                                    fontSize: 8,
                                    color: AppColors.textMuted,
                                  ),
                            )
                          : const SizedBox.shrink(),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Map a 0-100 score to a pixel offset from the top of the 34px plot area.
  ///
  /// The plot is not the full 0-100 range at the top: a hair of headroom keeps
  /// an 80 from touching the frame, and the bottom stops above the axis line.
  double _scoreY(double score) {
    const top = 2.0;
    const usable = 30.0;
    return top + usable * (1.0 - score.clamp(0, 100) / 100.0);
  }

  /// One day in the plot: a stem from the axis up to the score, a filled dot at
  /// the score, or an explicit "no measurement" mark.
  Widget _dayMark(PfzHistoryDay day) {
    if (!day.hasScore) {
      // Present, and explicitly not a value. A hollow dot on the axis baseline
      // with a dash says "we looked and could not score this", where a dot at
      // the bottom of the scale would say "this place is worthless".
      return Stack(
        alignment: Alignment.topCenter,
        children: [
          Positioned(
            top: _scoreY(0),
            child: Container(
              width: 5,
              height: 5,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.textMuted, width: 1),
              ),
            ),
          ),
          Positioned(
            top: _scoreY(0) + 7,
            child: Container(width: 7, height: 1, color: AppColors.textMuted),
          ),
        ],
      );
    }

    final score = day.topPfz!;
    final y = _scoreY(score.toDouble());

    return Stack(
      alignment: Alignment.topCenter,
      children: [
        // A stem from the axis up to the dot, so the mark reads as belonging to
        // a day on the axis rather than floating in the plot.
        Positioned(
          top: y,
          bottom: _scoreY(0) - y,
          child: Container(width: 1, color: AppColors.border),
        ),
        Positioned(
          top: y - 3,
          child: Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: AppColors.getScoreColor(score),
              shape: BoxShape.circle,
            ),
          ),
        ),
      ],
    );
  }

  /// Cells whose score actually moved, best improvement first.
  Widget _cellMovers(PfzZoneHistory history) {
    final movers = PfzHistoryCell.movers(history.cells).take(3).toList();
    if (movers.isEmpty) return const SizedBox.shrink();

    return Wrap(
      spacing: 8,
      runSpacing: 4,
      children: [
        for (final cell in movers)
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '${cell.lat.toStringAsFixed(2)}, ${cell.lon.toStringAsFixed(2)}',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: AppColors.textSecondary,
                      fontSize: 10,
                    ),
              ),
              const SizedBox(width: 4),
              Text(
                cell.change! > 0
                    ? '+${cell.change}'
                    : '${cell.change}',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      color: (cell.change ?? 0) > 0
                          ? AppColors.scoreExcellent
                          : AppColors.scoreBelowAvg,
                    ),
              ),
            ],
          ),
      ],
    );
  }
}

class _LegendItem extends StatelessWidget {
  final Color color;
  final String label;

  const _LegendItem({required this.color, required this.label});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 10,
          height: 10,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Text(
          label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(fontSize: 10),
        ),
      ],
    );
  }
}

class _SpeciesPickerSheet extends StatefulWidget {
  final List<PfzSpecies> catalog;
  final String selectedId;
  final String region;

  const _SpeciesPickerSheet({
    required this.catalog,
    required this.selectedId,
    required this.region,
  });

  @override
  State<_SpeciesPickerSheet> createState() => _SpeciesPickerSheetState();
}

class _SpeciesPickerSheetState extends State<_SpeciesPickerSheet> {
  final TextEditingController _search = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final query = _query.trim().toLowerCase();
    final filtered = query.isEmpty
        ? widget.catalog
        : widget.catalog
              .where(
                (s) =>
                    s.label.toLowerCase().contains(query) ||
                    s.scientificName.toLowerCase().contains(query),
              )
              .toList();

    final grouped = <String, List<PfzSpecies>>{};
    for (final s in filtered) {
      grouped.putIfAbsent(s.category, () => []).add(s);
    }

    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.72,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 32,
                height: 4,
                margin: const EdgeInsets.only(top: 10, bottom: 6),
                decoration: BoxDecoration(
                  color: AppColors.textMuted.withOpacity(0.4),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                children: [
                  Text('Species', style: theme.textTheme.titleMedium),
                  const Spacer(),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 3,
                    ),
                    decoration: BoxDecoration(
                      color: AppColors.background,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      widget.region,
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: TextField(
                controller: _search,
                onChanged: (v) => setState(() => _query = v),
                style: theme.textTheme.bodyMedium,
                decoration: InputDecoration(
                  hintText: 'Search species...',
                  hintStyle: theme.textTheme.bodyMedium?.copyWith(
                    color: AppColors.textMuted,
                  ),
                  prefixIcon: const Icon(
                    Icons.search,
                    color: AppColors.textSecondary,
                  ),
                  suffixIcon: _query.isEmpty
                      ? null
                      : IconButton(
                          icon: const Icon(
                            Icons.close,
                            size: 18,
                            color: AppColors.textSecondary,
                          ),
                          onPressed: () {
                            _search.clear();
                            setState(() => _query = '');
                          },
                        ),
                  filled: true,
                  fillColor: AppColors.background,
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 12,
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(10),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 4),
            if (filtered.isEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
                child: Text(
                  'No species matches that name.',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: AppColors.textMuted,
                  ),
                ),
              )
            else
              Expanded(
                child: ListView(
                  shrinkWrap: true,
                  padding: const EdgeInsets.only(bottom: 12),
                  children: [
                    for (final entry in grouped.entries) ...[
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 2),
                        child: Text(
                          entry.key.toUpperCase(),
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: AppColors.textMuted,
                            fontWeight: FontWeight.w600,
                            letterSpacing: 0.8,
                          ),
                        ),
                      ),
                      for (final s in entry.value)
                        ListTile(
                          dense: true,
                          contentPadding:
                              const EdgeInsets.symmetric(horizontal: 16),
                          title: Text(
                            s.label,
                            style: theme.textTheme.bodyMedium,
                          ),
                          subtitle: Text(
                            s.scientificName,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: AppColors.textMuted,
                            ),
                          ),
                          trailing: Icon(
                            s.id == widget.selectedId
                                ? Icons.radio_button_checked
                                : Icons.radio_button_unchecked,
                            color: s.id == widget.selectedId
                                ? AppColors.scoreExcellent
                                : AppColors.textMuted,
                          ),
                          onTap: () => Navigator.of(context).pop(s.id),
                        ),
                    ],
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}