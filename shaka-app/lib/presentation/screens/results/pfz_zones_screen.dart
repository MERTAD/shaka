import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:maplibre_gl/maplibre_gl.dart';
import '../../../core/theme/app_colors.dart';
import '../../../data/api/shaka_api_client.dart';
import '../../../data/models/pfz_models.dart';

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
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
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
          else
            Expanded(child: Stack(children: [_map(), _legend(), _summaryPanel()])),
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