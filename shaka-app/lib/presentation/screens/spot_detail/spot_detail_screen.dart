import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../../core/theme/app_colors.dart';
import '../../../data/api/shaka_api_client.dart';
import '../../../data/models/spot_models.dart';
import '../../../data/models/pfz_models.dart';
import '../../../data/services/live_wind_service.dart';
import '../../bloc/search_bloc.dart';
import '../../widgets/conditions_card.dart';
import '../../widgets/pfz_card.dart';
import '../../widgets/live_wind_value.dart';
import '../../widgets/satellite_readings_card.dart';
import '../../widgets/swell_details_card.dart';
import '../../widgets/tide_chart_card.dart';
import '../../widgets/swell_chart_card.dart';
import '../../widgets/wind_chart_card.dart';
import '../../widgets/score_tier_pill.dart';
import '../../widgets/spot_ocean_forecast_card.dart';
import '../charts/ocean_forecast_screen.dart';

class SpotDetailScreen extends StatefulWidget {
  final String spotId;
  final String date;
  final SpotSummary? preloadedSpot;
  final bool isUserSpot;

  const SpotDetailScreen({
    super.key,
    required this.spotId,
    required this.date,
    this.preloadedSpot,
    this.isUserSpot = false,
  });

  @override
  State<SpotDetailScreen> createState() => _SpotDetailScreenState();
}

class _SpotDetailScreenState extends State<SpotDetailScreen>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  bool _hasLoaded = false;
  late TabController _tabController;

  // Dark theme colors
  static const _bgColor = AppColors.darkBackground;
  static const _cardColor = AppColors.darkSurface;
  static const _borderColor = AppColors.darkBorder;

  // User spot handling
  final _apiClient = ShakaApiClient();
  SpotDetail? _userSpotDetail;
  bool _userSpotLoading = false;
  String? _userSpotError;

  // Lazy-loaded forecast
  List<DayForecast>? _forecast;
  bool _forecastLoading = false;
  bool _tilesPrecached = false;

  // Near-real-time wind, fetched AFTER the detail paints so the load stays
  // instant. The Conditions card shows a "checking..." cue while this is in
  // flight and updates the Wind reading in place (with a "Live" indicator) once
  // it resolves.
  LiveWind? _liveWind;
  bool _liveWindLoading = false;
  bool _liveWindRequested = false;

  // Lazy-loaded hourly swell/wind + multi-day tide curves (chart data).
  SpotHourlyResponse? _hourly;
  bool _hourlyLoading = false;
  SpotTideRangeResponse? _tideRange;
  bool _tideRangeLoading = false;
  int _selectedForecastIndex = 0;

  // Species-specific PFZ verdicts. Lazy-loaded after paint for the same reason
  // as live wind: it must never delay the detail view. Skipped for user spots
  // because the endpoint resolves curated spots only.
  PfzResponse? _pfz;
  bool _pfzLoading = false;
  String? _pfzError;

  /// Cache id used by the hourly/tide chart endpoints (user spots are prefixed).
  String get _cacheId =>
      widget.isUserSpot ? 'user-${widget.spotId}' : widget.spotId;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _tabController = TabController(length: 3, vsync: this);
    _loadSpotDetail();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _tabController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && widget.isUserSpot) {
      _loadUserSpotDetail();
    }
  }

  void _loadSpotDetail() {
    if (_hasLoaded) return;
    _hasLoaded = true;
    
    if (widget.isUserSpot) {
      _loadUserSpotDetail();
    } else {
      context.read<SearchBloc>().add(LoadSpotDetail(
            spotId: widget.spotId,
            date: widget.date,
          ));
    }
  }

  Future<void> _loadUserSpotDetail() async {
    setState(() => _userSpotLoading = true);
    try {
      final response = await _apiClient.getUserSpotDetail(
        spotId: widget.spotId,
        date: widget.date,
      );
      if (mounted) {
        setState(() {
          _userSpotDetail = response.spot;
          _userSpotLoading = false;
        });
        if (_needsPolling(response.spot)) {
          _pollForMissingData();
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _userSpotError = e.toString();
          _userSpotLoading = false;
        });
      }
    }
  }

  bool _needsPolling(SpotDetail spot) {
    if (spot.regulations?.mpaChecked == false) return true;
    if (spot.tide == null || spot.tide!.points.isEmpty) return true;
    return false;
  }

  Future<void> _pollForMissingData() async {
    int attempts = 0;
    while (mounted && attempts < 10) {
      await Future.delayed(const Duration(seconds: 3));
      if (!mounted) return;
      attempts++;
      try {
        final response = await _apiClient.getUserSpotDetail(
          spotId: widget.spotId,
          date: widget.date,
        );
        if (mounted) {
          setState(() => _userSpotDetail = response.spot);
        }
        if (!_needsPolling(response.spot)) return;
      } catch (_) {}
    }
  }

  /// Fetch near-real-time wind once, after the detail page has painted. Kept off
  /// the initial load path so the page renders instantly from cached data; the
  /// Wind reading then updates in place when this resolves.
  void _maybeLoadLiveWind() {
    if (_liveWindRequested) return;
    _liveWindRequested = true;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      setState(() => _liveWindLoading = true);
      try {
        // Shared service (not a direct client call) so the detail screen and
        // the spot cards resolve the same cached reading.
        final live = await LiveWindService.instance.get(_cacheId);
        if (mounted) {
          setState(() {
            _liveWind = live;
            _liveWindLoading = false;
          });
        }
      } catch (_) {
        if (mounted) setState(() => _liveWindLoading = false);
      }
    });
  }

  /// Lazy-load forecast when user taps the Forecast tab.
  Future<void> _loadForecast() async {
    if (_forecast != null || _forecastLoading) return;
    setState(() => _forecastLoading = true);
    try {
      final data = await _apiClient.getForecast(
        spotId: widget.spotId,
        days: 5,
      );
      if (mounted) {
        setState(() {
          _forecast = data;
          _forecastLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _forecast = [];
          _forecastLoading = false;
        });
      }
    }
  }

  /// Lazy-load hourly swell/wind curves (grouped by spot-local day).
  Future<void> _loadHourly() async {
    if (_hourly != null || _hourlyLoading) return;
    setState(() => _hourlyLoading = true);
    try {
      final data = await _apiClient.getSpotHourly(_cacheId);
      if (mounted) {
        setState(() {
          _hourly = data ?? const SpotHourlyResponse(
              spotId: '', timezoneId: null, days: []);
          _hourlyLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _hourly = const SpotHourlyResponse(
              spotId: '', timezoneId: null, days: []);
          _hourlyLoading = false;
        });
      }
    }
  }

  /// Lazy-load multi-day tide curves for the forecast tab.
  Future<void> _loadTideRange() async {
    if (_tideRange != null || _tideRangeLoading) return;
    setState(() => _tideRangeLoading = true);
    try {
      final data = await _apiClient.getTideRange(_cacheId, days: 7);
      if (mounted) {
        setState(() {
          _tideRange = data ?? const SpotTideRangeResponse(
              spotId: '', timezoneId: null, days: []);
          _tideRangeLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _tideRange = const SpotTideRangeResponse(
              spotId: '', timezoneId: null, days: []);
          _tideRangeLoading = false;
        });
      }
    }
  }

  /// Lazy-load species-specific PFZ verdicts for this spot and date.
  ///
  /// A 404 (unknown/curated-only spot) is a normal outcome, not an error: the
  /// card simply does not render. Any other failure surfaces as text so the
  /// angler can tell "we don't know" apart from "it's bad".
  Future<void> _loadPfz() async {
    if (_pfz != null || _pfzLoading || _pfzError != null) return;
    if (widget.isUserSpot) return; // endpoint resolves curated spots only
    setState(() => _pfzLoading = true);
    try {
      final data = await _apiClient.getPfz(
        spotId: widget.spotId,
        date: widget.date,
      );
      if (mounted) {
        setState(() {
          _pfz = data;
          _pfzLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _pfzError = 'Could not reach the scoring service.';
          _pfzLoading = false;
        });
      }
    }
  }

  /// Today's grouped hourly points (days[0]), or null until loaded.
  SpotHourlyDay? get _todayHourly =>
      (_hourly != null && _hourly!.days.isNotEmpty) ? _hourly!.days.first : null;

  /// The hourly day matching a forecast day, by local date then index.
  SpotHourlyDay? _hourlyForForecast(int index, String forecastDate) {
    final days = _hourly?.days;
    if (days == null || days.isEmpty) return null;
    final dateKey = forecastDate.split('T').first;
    for (final d in days) {
      if (d.localDate == dateKey) return d;
    }
    return index < days.length ? days[index] : null;
  }

  /// The tide chart day matching a forecast day, by local date then index.
  TideChartData? _tideForForecast(int index, String forecastDate) {
    final days = _tideRange?.days;
    if (days == null || days.isEmpty) return null;
    final dateKey = forecastDate.split('T').first;
    for (final d in days) {
      if (d.localDate == dateKey) return d;
    }
    return index < days.length ? days[index] : null;
  }

  @override
  Widget build(BuildContext context) {
    // Handle user spot case separately
    if (widget.isUserSpot) {
      return Scaffold(
        backgroundColor: _bgColor,
        body: _buildUserSpotContent(),
      );
    }

    return Scaffold(
      backgroundColor: _bgColor,
      body: BlocBuilder<SearchBloc, SearchState>(
        builder: (context, state) {
          if (state is SpotDetailSuccess) {
            return _buildTabbedContent(state.spot);
          }

          if (state is SearchError) {
            return _buildError(state.message);
          }

          if (widget.preloadedSpot != null) {
            return _buildPreloadedContent(widget.preloadedSpot!);
          }

          return _buildLoadingState();
        },
      ),
    );
  }

  Widget _buildUserSpotContent() {
    if (_userSpotLoading) {
      return _buildLoadingState();
    }
    
    if (_userSpotError != null) {
      return _buildError(_userSpotError!);
    }
    
    if (_userSpotDetail != null) {
      return _buildTabbedContent(_userSpotDetail!);
    }
    
    return _buildLoadingState();
  }

  void _precacheSatelliteTiles(Coordinates coords) {
    if (_tilesPrecached) return;
    _tilesPrecached = true;
    SwellDetailsCard.precacheTiles(context, coords.lat, coords.lon);
  }

  /// Full tabbed content when SpotDetail is loaded
  Widget _buildTabbedContent(SpotDetail spot) {
    _precacheSatelliteTiles(spot.coordinates);
    _maybeLoadLiveWind();
    return NestedScrollView(
      headerSliverBuilder: (context, innerBoxIsScrolled) {
        return [
          // Header with back button and spot info
          SliverAppBar(
            expandedHeight: 160,
            pinned: true,
            backgroundColor: _bgColor,
            leading: const SizedBox.shrink(),
            leadingWidth: 0,
            flexibleSpace: FlexibleSpaceBar(
              background: _buildHeader(spot),
            ),
            bottom: PreferredSize(
              preferredSize: const Size.fromHeight(48),
              child: _buildTabBar(
                hasRestriction: spot.regulations?.mpaStatus?.isInsideMPA == true,
              ),
            ),
          ),
        ];
      },
      body: TabBarView(
        controller: _tabController,
        children: [
          _buildCurrentTab(spot),
          _buildForecastTab(spot),
          _buildGuideTab(spot),
        ],
      ),
    );
  }

  Widget _buildHeader(SpotDetail spot) {
    final safeTop = MediaQuery.of(context).padding.top;
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [AppColors.oceanBlue, _bgColor],
        ),
      ),
      padding: EdgeInsets.fromLTRB(8, safeTop, 20, 48),
      alignment: Alignment.centerLeft,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          IconButton(
            onPressed: () {
              HapticFeedback.lightImpact();
              context.pop();
            },
            icon: const Icon(Icons.arrow_back, color: Colors.white),
          ),
          Expanded(
            child: Text(
              spot.name,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 22,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          const SizedBox(width: 12),
          ScoreTierPill(score: spot.score.overall, width: 14, height: 48, vertical: true),
        ],
      ),
    );
  }

  Widget _buildTabBar({bool hasRestriction = false}) {
    return Container(
      color: _bgColor,
      child: TabBar(
        controller: _tabController,
        indicatorColor: AppColors.info,
        indicatorWeight: 3,
        labelColor: Colors.white,
        unselectedLabelColor: AppColors.darkTextMuted,
        labelStyle: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
        tabs: [
          const Tab(text: 'Conditions'),
          const Tab(text: 'Forecast'),
          Tab(
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                const Text('Regulations'),
                if (hasRestriction)
                  Positioned(
                    right: -8,
                    top: -2,
                    child: Container(
                      width: 7,
                      height: 7,
                      decoration: BoxDecoration(
                        color: AppColors.error,
                        shape: BoxShape.circle,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// CURRENT TAB - Live conditions, score breakdown, risks
  Widget _buildCurrentTab(SpotDetail spot) {
    // Kick off the hourly swell/wind fetch on first view (used by both tabs).
    if (_hourly == null && !_hourlyLoading) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadHourly());
    }
    // Species scoring is secondary to conditions, so it loads after paint too.
    if (_pfz == null && !_pfzLoading && _pfzError == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadPfz());
    }
    final todayHourly = _todayHourly;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // Conditions with current date
        Row(
          children: [
            _buildSectionHeader('CONDITIONS'),
            const Spacer(),
            Text(
              DateFormat('EEEE, MMM d').format(DateTime.now()),
              style: const TextStyle(
                color: AppColors.darkTextHint,
                fontSize: 11,
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        ConditionsCard(
                conditions: spot.conditions,
                satelliteReadings: spot.satelliteReadings,
                liveWindSpeedKts: _liveWind?.windSpeedKts,
                liveWindDirectionCardinal: _liveWind?.windDirectionCardinal,
                liveWindRetrievedAt: _liveWind?.retrievedAt,
                liveWindLoading: _liveWindLoading,
                utcOffsetMinutes: _hourly?.utcOffsetMinutes,
                timezoneAbbr: _hourly?.timezoneAbbr,
              ),

        const SizedBox(height: 20),

        // Score Breakdown
        _buildSectionHeader('SCORE BREAKDOWN'),
        const SizedBox(height: 10),
        _buildScoreBreakdown(spot.score.breakdown),

        const SizedBox(height: 20),

        // Swell details (expandable)
        _buildSectionHeader('SWELL & WIND'),
        const SizedBox(height: 10),
        SwellDetailsCard(conditions: spot.conditions, coordinates: spot.coordinates, liveWind: _liveWind),
        const SizedBox(height: 20),

        // Tides (expandable, only when data available)
        if (spot.tide != null && spot.tide!.points.isNotEmpty) ...[
          _buildSectionHeader('TIDES'),
          const SizedBox(height: 10),
          TideChartCard(
            tide: spot.tide!,
            utcOffsetMinutes: _hourly?.utcOffsetMinutes,
            timezoneAbbr: _hourly?.timezoneAbbr,
          ),
          const SizedBox(height: 20),
        ],

        // Intraday swell + wind curves for today (loaded lazily).
        if (todayHourly != null && todayHourly.swell.isNotEmpty) ...[
          _buildSectionHeader('SWELL'),
          const SizedBox(height: 10),
          SwellChartCard(
            points: todayHourly.swell,
            isToday: true,
            utcOffsetMinutes: _hourly?.utcOffsetMinutes,
            timezoneAbbr: _hourly?.timezoneAbbr,
          ),
          const SizedBox(height: 20),
        ],
        if (todayHourly != null && todayHourly.wind.isNotEmpty) ...[
          _buildSectionHeader('WIND'),
          const SizedBox(height: 10),
          WindChartCard(
            points: todayHourly.wind,
            isToday: true,
            spotId: _cacheId,
            utcOffsetMinutes: _hourly?.utcOffsetMinutes,
            timezoneAbbr: _hourly?.timezoneAbbr,
          ),
          const SizedBox(height: 20),
        ],

        // Satellite Visibility (collapsed label, expandable details)
        if (spot.satelliteReadings != null && spot.satelliteReadings!.hasAnyData) ...[
          _buildSectionHeader('VISIBILITY'),
          const SizedBox(height: 10),
          SatelliteReadingsCard(
            readings: spot.satelliteReadings,
            visibilityScore: spot.score.breakdown.visibility,
          ),
          const SizedBox(height: 20),
        ],

        // Risks
        if (spot.risks.isNotEmpty) ...[
          _buildSectionHeader('RISKS'),
          const SizedBox(height: 10),
          _buildRisksList(spot.risks),
        ],

        const SizedBox(height: 24),

        // Ocean Charts section
        _buildSectionHeader('OCEAN DATA'),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: _buildChartButton(
                icon: Icons.air,
                label: 'Forecast',
                sublabel: 'Ocean Data',
                color: const Color(0xFF00BCD4),
                onTap: () {
                  HapticFeedback.lightImpact();
                  Navigator.of(context, rootNavigator: true).push(
                    MaterialPageRoute(
                      builder: (context) => OceanForecastScreen(
                        initialLat: spot.coordinates.lat,
                        initialLon: spot.coordinates.lon,
                        spotName: spot.name,
                        utcOffsetMinutes: _hourly?.utcOffsetMinutes,
                        timezoneAbbr: _hourly?.timezoneAbbr,
                      ),
                    ),
                  );
                },
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _buildChartButton(
                icon: Icons.satellite_alt,
                label: 'Satellite',
                sublabel: 'NASA GIBS',
                color: AppColors.success,
                onTap: () {
                  HapticFeedback.lightImpact();
                  context.push('/charts/gibs', extra: {
                    'lat': spot.coordinates.lat,
                    'lon': spot.coordinates.lon,
                    'spotName': spot.name,
                  });
                },
              ),
            ),
          ],
        ),

        const SizedBox(height: 40),
      ],
    );
  }

  /// FORECAST TAB - Lazy-loaded when user taps this tab
  Widget _buildForecastTab(SpotDetail spot) {
    // Trigger lazy load on first view
    if (_forecast == null && !_forecastLoading) {
      // Schedule fetch after build
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadForecast());
    }

    // Loading state
    if (_forecastLoading) {
      return const Center(
        child: CircularProgressIndicator(
          strokeWidth: 2,
          color: AppColors.info,
        ),
      );
    }

    final forecast = _forecast;
    if (forecast == null || forecast.isEmpty) {
      return const Center(
        child: Text(
          'Forecast data unavailable',
          style: TextStyle(color: AppColors.darkTextMuted),
        ),
      );
    }

    // Chart data for the tabs is shared with the Conditions tab; load lazily.
    if (_hourly == null && !_hourlyLoading) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadHourly());
    }
    if (_tideRange == null && !_tideRangeLoading) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadTideRange());
    }

    final selected = _selectedForecastIndex.clamp(0, forecast.length - 1);
    final selectedDay = forecast[selected];

    return ListView(
      padding: const EdgeInsets.all(16),
      physics: const ClampingScrollPhysics(),
      children: [
        for (int i = 0; i < forecast.length; i++)
          _buildForecastCard(forecast[i], i == 0,
              isSelected: i == selected,
              onTap: () => setState(() => _selectedForecastIndex = i)),
        const SizedBox(height: 12),
        ..._buildSelectedDayCharts(selected, selectedDay),
        const SizedBox(height: 8),
        SpotOceanForecastCard(
          lat: spot.coordinates.lat,
          lon: spot.coordinates.lon,
          targetDate: _forecastDate(selectedDay.date),
          utcOffsetMinutes: _hourly?.utcOffsetMinutes,
          timezoneAbbr: _hourly?.timezoneAbbr,
        ),
      ],
    );
  }

  /// Day-scoped Tide/Swell/Wind charts for the selected forecast day, reusing
  /// the same widgets as the Conditions tab.
  List<Widget> _buildSelectedDayCharts(int index, DayForecast day) {
    final isToday = index == 0;
    final tide = _tideForForecast(index, day.date);
    final hourly = _hourlyForForecast(index, day.date);

    final label = isToday ? 'Today' : _formatDate(day.date);
    final widgets = <Widget>[
      Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: _buildSectionHeader('$label \u00b7 DETAIL'),
      ),
    ];

    if (tide != null && tide.points.isNotEmpty) {
      widgets.add(TideChartCard(
        tide: tide,
        utcOffsetMinutes: _hourly?.utcOffsetMinutes,
        timezoneAbbr: _hourly?.timezoneAbbr,
      ));
      widgets.add(const SizedBox(height: 16));
    }
    if (hourly != null && hourly.swell.isNotEmpty) {
      widgets.add(SwellChartCard(
        points: hourly.swell,
        isToday: isToday,
        utcOffsetMinutes: _hourly?.utcOffsetMinutes,
        timezoneAbbr: _hourly?.timezoneAbbr,
      ));
      widgets.add(const SizedBox(height: 16));
    }
    if (hourly != null && hourly.wind.isNotEmpty) {
      widgets.add(WindChartCard(
        points: hourly.wind,
        isToday: isToday,
        spotId: _cacheId,
        utcOffsetMinutes: _hourly?.utcOffsetMinutes,
        timezoneAbbr: _hourly?.timezoneAbbr,
      ));
      widgets.add(const SizedBox(height: 16));
    }

    final loading = _hourlyLoading || _tideRangeLoading;
    if (widgets.length == 1 && loading) {
      widgets.add(const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(
          child: CircularProgressIndicator(
              strokeWidth: 2, color: AppColors.info),
        ),
      ));
    } else if (widgets.length == 1) {
      widgets.add(const Padding(
        padding: EdgeInsets.symmetric(vertical: 16),
        child: Text('Detailed charts unavailable for this day',
            style: TextStyle(color: AppColors.darkTextMuted, fontSize: 13)),
      ));
    }
    return widgets;
  }

  DateTime? _forecastDate(String date) {
    try {
      return DateTime.parse(date.split('T').first);
    } catch (_) {
      return null;
    }
  }

  Widget _buildForecastCard(DayForecast day, bool isToday,
      {bool isSelected = false, VoidCallback? onTap}) {
    return GestureDetector(
      onTap: () {
        HapticFeedback.selectionClick();
        onTap?.call();
      },
      child: Container(
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: isSelected
              ? AppColors.info.withOpacity(0.10)
              : _cardColor,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isSelected ? AppColors.info : _borderColor,
            width: isSelected ? 1.5 : 1,
          ),
        ),
        child: Row(
          children: [
            // Date
            SizedBox(
              width: 70,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    isToday ? 'Today' : _formatDate(day.date),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Text(
                    _formatWeekday(day.date),
                    style: const TextStyle(
                        color: AppColors.darkTextMuted, fontSize: 11),
                  ),
                ],
              ),
            ),

            // Score + tier pill
            ScoreTierPill(score: day.shakaScore, width: 40, height: 10),

            const SizedBox(width: 14),

            // Conditions summary with icons
            Expanded(
              child: Row(
                children: [
                  _buildForecastCondition(Icons.visibility,
                      day.conditions.visibility.split(' ').first),
                  const SizedBox(width: 10),
                  _buildForecastCondition(Icons.waves,
                      day.conditions.swell.split('@').first.trim()),
                  const SizedBox(width: 10),
                  // Today's wind is a CURRENT surface: it reads the same
                  // shared near-real-time value as the spot cards and chart
                  // header (live-preferred, snapshot forecast as fallback).
                  // Future days keep the server's forecast sample.
                  if (isToday)
                    Flexible(
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.air,
                              color: AppColors.darkTextMuted, size: 14),
                          const SizedBox(width: 4),
                          Flexible(
                            child: CurrentWindText(
                              spotId: _cacheId,
                              fallback: day.conditions.wind,
                              style: const TextStyle(
                                color: AppColors.darkTextSecondary,
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ),
                        ],
                      ),
                    )
                  else
                    _buildForecastCondition(Icons.air, day.conditions.wind),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildForecastCondition(IconData icon, String value) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: AppColors.darkTextMuted, size: 14),
        const SizedBox(width: 4),
        Text(
          value,
          style: const TextStyle(
            color: AppColors.darkTextSecondary,
            fontSize: 12,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }

  /// REGULATIONS TAB - MPA warnings, regulations
  Widget _buildGuideTab(SpotDetail spot) {
    return ListView(
      padding: const EdgeInsets.all(16),
      physics: const NeverScrollableScrollPhysics(),
      children: [
        if (spot.regulations != null) ...[
          _buildRegulationsInfo(spot.regulations!),
          const SizedBox(height: 24),
        ],
        const SizedBox(height: 40),
      ],
    );
  }

  // ============ HELPER WIDGETS ============

  Widget _buildSectionHeader(String title) {
    return Text(
      title,
      style: TextStyle(
        color: AppColors.darkTextMuted,
        fontSize: 11,
        fontWeight: FontWeight.w600,
        letterSpacing: 1.2,
      ),
    );
  }
  
  /// Build a chart shortcut button
  Widget _buildChartButton({
    required IconData icon,
    required String label,
    required String sublabel,
    required Color color,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: _cardColor,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withOpacity(0.3)),
        ),
        child: Row(
          children: [
            Icon(icon, color: color, size: 24),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    label,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Text(
                    sublabel,
                    style: TextStyle(
                      color: AppColors.darkTextMuted,
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildScoreBreakdown(ScoreBreakdown breakdown) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: _cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: _borderColor),
      ),
      child: Column(
        children: [
          _buildScoreRow('Swell', breakdown.swell),
          _buildScoreRow('Wind', breakdown.weather),
          _buildScoreRow('Visibility', breakdown.visibility),
          _buildScoreRow('Solunar', breakdown.solunar, isLast: true),
        ],
      ),
    );
  }

  Widget _buildScoreRow(String label, int score, {bool isLast = false}) {
    return Container(
      padding: EdgeInsets.only(bottom: isLast ? 0 : 10, top: isLast ? 0 : 0),
      decoration: BoxDecoration(
        border: isLast
            ? null
            : const Border(bottom: BorderSide(color: Colors.white10)),
      ),
      margin: EdgeInsets.only(bottom: isLast ? 0 : 10),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: const TextStyle(color: AppColors.darkTextSecondary, fontSize: 13),
            ),
          ),
          Expanded(
            flex: 2,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                Expanded(
                  child: Container(
                    height: 6,
                    decoration: BoxDecoration(
                      color: AppColors.darkTextHint,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: FractionallySizedBox(
                      alignment: Alignment.centerLeft,
                      widthFactor: score / 100,
                      child: Container(
                        decoration: BoxDecoration(
                          color: _getScoreColor(score),
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                SizedBox(
                  width: 36,
                  child: Text(
                    '$score',
                    style: TextStyle(
                      color: _getScoreColor(score),
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                    maxLines: 1,
                    softWrap: false,
                    overflow: TextOverflow.visible,
                    textAlign: TextAlign.right,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRisksList(List<RiskInfo> risks) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: _cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: _borderColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: risks
            .map((r) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(
                    r.risk,
                    style: const TextStyle(
                        color: AppColors.darkTextSecondary, fontSize: 13),
                  ),
                ))
            .toList(),
      ),
    );
  }

  Widget _buildAccessInfo(AccessInfo access) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: _cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: _borderColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            access.directions,
            style: const TextStyle(
                color: Colors.white, fontSize: 13, height: 1.4),
          ),
          const SizedBox(height: 10),
          Text(
            access.parkingInfo,
            style: const TextStyle(color: AppColors.darkTextMuted, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Widget _buildRegulationsInfo(RegulationInfo regulations) {
    final mpa = regulations.mpaStatus;
    
    // Determine MPA status color and text based on isInsideMPA
    Color statusColor;
    String statusText;
    IconData? statusIcon;
    String? detailText;
    
    if (mpa == null && !regulations.mpaChecked) {
      // MPA check hasn't run yet -- show loading state (not the false "No Restrictions")
      statusColor = Colors.grey;
      statusText = 'Checking MPA restrictions...';
      statusIcon = null; // will render a spinner instead
    } else if (mpa == null || mpa.spearfishingStatus == 0) {
      // MPA was checked, no restrictions found (safe to show green)
      statusColor = AppColors.success;
      statusText = 'No MPA restrictions nearby';
      statusIcon = Icons.check_circle;
    } else if (mpa.isInsideMPA) {
      // INSIDE a restricted area - RED warning
      statusColor = AppColors.error;
      statusIcon = Icons.block;
      if (mpa.spearfishingStatus == 1) {
        statusText = 'RESTRICTED AREA - FREEDIVING ONLY';
        detailText = 'This spot is inside "${mpa.siteName}". Spearfishing is prohibited.';
      } else {
        statusText = 'RESTRICTED AREA - CHECK REGULATIONS';
        detailText = 'This spot is inside "${mpa.siteName}". Special regulations apply.';
      }
    } else {
      // NEARBY a restricted area - ORANGE warning
      statusColor = AppColors.warning;
      if (mpa.spearfishingStatus == 1) {
        statusText = 'MARINE SANCTUARY NEARBY';
        statusIcon = Icons.warning_amber;
        detailText = '"${mpa.siteName}" is within 1.5km';
      } else if (mpa.spearfishingStatus == 2) {
        statusText = 'RESTRICTED AREA NEARBY';
        statusIcon = Icons.warning;
        detailText = '"${mpa.siteName}" is within 1.5km';
      } else {
        statusText = 'Verify local regulations';
        statusIcon = Icons.help_outline;
      }
    }
    
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // MPA Protection Status Card
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: statusColor.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: statusColor.withValues(alpha: 0.3)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  if (statusIcon != null)
                    Icon(statusIcon, color: statusColor, size: 20)
                  else
                    SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: statusColor,
                      ),
                    ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      statusText,
                      style: TextStyle(
                        color: statusColor,
                        fontWeight: FontWeight.bold,
                        fontSize: 14,
                      ),
                    ),
                  ),
                ],
              ),
              if (mpa != null && mpa.siteName != null) ...[
                const SizedBox(height: 8),
                Text(
                  detailText ?? '"${mpa.siteName!}" is within 1.5km',
                  style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500),
                ),
                if (mpa.designation != null)
                  Text(
                    mpa.designation!,
                    style: const TextStyle(color: AppColors.darkTextMuted, fontSize: 12),
                  ),
                if (mpa.isInsideMPA && mpa.spearfishingStatus == 1) ...[
                  const SizedBox(height: 8),
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: AppColors.error.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(6),
                      border: Border.all(color: AppColors.error.withValues(alpha: 0.3)),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.block, color: AppColors.error, size: 16),
                        SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Spearfishing is NOT allowed here. Freedive only or find another spot.',
                            style: TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w500),
                          ),
                        ),
                      ],
                    ),
                  ),
                ] else if (!mpa.isInsideMPA && mpa.spearfishingStatus == 1) ...[
                  const SizedBox(height: 6),
                  const Text(
                    'Spearfishing is PROHIBITED inside sanctuary boundaries.',
                    style: TextStyle(color: AppColors.darkTextSecondary, fontSize: 12),
                  ),
                  const SizedBox(height: 8),
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: AppColors.warning.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(6),
                      border: Border.all(color: AppColors.warning.withValues(alpha: 0.3)),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.info_outline, color: AppColors.warning, size: 16),
                        SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Know exactly where the sanctuary begins and ends before entering the water.',
                            style: TextStyle(color: AppColors.darkTextSecondary, fontSize: 11),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
              if (mpa?.detailsUrl != null) ...[
                const SizedBox(height: 10),
                GestureDetector(
                  onTap: () => _launchUrl(mpa!.detailsUrl!),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    decoration: BoxDecoration(
                      color: AppColors.info.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.map, color: AppColors.info, size: 16),
                        SizedBox(width: 6),
                        Text(
                          'View official boundary map',
                          style: TextStyle(color: AppColors.info, fontSize: 12, fontWeight: FontWeight.w500),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
        
        const SizedBox(height: 12),
        
        // Regulatory Agency Info
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: _cardColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: _borderColor),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                regulations.regulatoryAgency,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                ),
              ),
              const SizedBox(height: 8),
              _buildLicenseBadge(regulations.licenseRequirement),
              if (regulations.note != null) ...[
                const SizedBox(height: 6),
                Text(
                  regulations.note!,
                  style: TextStyle(
                    color: AppColors.warning,
                    fontSize: 12,
                    fontStyle: FontStyle.italic,
                  ),
                ),
              ],
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: GestureDetector(
                      onTap: () => _launchUrl(regulations.regulationsUrl),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 12, vertical: 8),
                        decoration: BoxDecoration(
                          color: AppColors.info.withValues(alpha: 0.2),
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(Icons.public, color: AppColors.info, size: 16),
                            SizedBox(width: 6),
                            Text(
                              'Regulations',
                              style:
                                  TextStyle(color: AppColors.info, fontSize: 12),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  if (regulations.licensingUrl != null) ...[
                    const SizedBox(width: 8),
                    Expanded(
                      child: GestureDetector(
                        onTap: () => _launchUrl(regulations.licensingUrl!),
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 12, vertical: 8),
                          decoration: BoxDecoration(
                            color: AppColors.success.withValues(alpha: 0.2),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(Icons.card_membership,
                                  color: AppColors.success, size: 16),
                              SizedBox(width: 6),
                              Text(
                                'Licenses',
                                style: TextStyle(
                                    color: AppColors.success, fontSize: 12),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Locale-level license requirement badge. Shown for EVERY spot so the
  /// Regulations tab is consistent; unknown states never imply "no license".
  Widget _buildLicenseBadge(String? requirement) {
    final Color color;
    final IconData icon;
    final String label;

    switch (requirement) {
      case 'required':
        color = AppColors.scoreBelowAvg; // orange
        icon = Icons.card_membership;
        label = 'License required';
        break;
      case 'conditional':
        color = AppColors.warning; // amber
        icon = Icons.card_membership;
        label = 'License may be required';
        break;
      case 'not_required':
        color = AppColors.success;
        icon = Icons.check_circle_outline;
        label = 'No license required';
        break;
      case 'prohibited':
        color = AppColors.error;
        icon = Icons.block;
        label = 'Spearfishing prohibited';
        break;
      default:
        // Unknown locale (or old server) -- never imply "not required".
        color = Colors.grey;
        icon = Icons.help_outline;
        label = 'Check local requirements';
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: color, size: 14),
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              label,
              style: TextStyle(
                color: color,
                fontSize: 12,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _formatFreshness(String isoDate) {
    try {
      final date = DateTime.parse(isoDate);
      final now = DateTime.now();
      final diff = now.difference(date);
      
      if (diff.inMinutes < 60) {
        return '${diff.inMinutes}m ago';
      } else if (diff.inHours < 24) {
        return '${diff.inHours}h ago';
      } else {
        return '${diff.inDays}d ago';
      }
    } catch (e) {
      return 'recently';
    }
  }

  Future<void> _launchUrl(String url) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  // ============ PRELOADED & LOADING STATES ============

  Widget _buildPreloadedContent(SpotSummary spot) {
    _precacheSatelliteTiles(spot.coordinates);
    return NestedScrollView(
      headerSliverBuilder: (context, innerBoxIsScrolled) {
        return [
          SliverAppBar(
            expandedHeight: 160,
            pinned: true,
            backgroundColor: _bgColor,
            leading: const SizedBox.shrink(),
            leadingWidth: 0,
            flexibleSpace: FlexibleSpaceBar(
              background: _buildPreloadedHeader(spot),
            ),
            bottom: PreferredSize(
              preferredSize: const Size.fromHeight(48),
              child: _buildTabBar(),
            ),
          ),
        ];
      },
      body: TabBarView(
        controller: _tabController,
        children: [
          // Current tab with preloaded data (includes cached satellite readings)
          ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _buildSectionHeader('CONDITIONS'),
              const SizedBox(height: 10),
              ConditionsCard(
                conditions: spot.conditions,
                satelliteReadings: spot.satelliteReadings,
                liveWindSpeedKts: _liveWind?.windSpeedKts,
                liveWindDirectionCardinal: _liveWind?.windDirectionCardinal,
                liveWindRetrievedAt: _liveWind?.retrievedAt,
                liveWindLoading: _liveWindLoading,
                utcOffsetMinutes: _hourly?.utcOffsetMinutes,
                timezoneAbbr: _hourly?.timezoneAbbr,
              ),
              if (_pfz != null || _pfzLoading || _pfzError != null) ...[
                const SizedBox(height: 20),
                PfzCard(pfz: _pfz, loading: _pfzLoading, error: _pfzError),
              ],
              const SizedBox(height: 20),
              // Swell details (expandable)
              _buildSectionHeader('SWELL & WIND'),
              const SizedBox(height: 10),
              SwellDetailsCard(conditions: spot.conditions, coordinates: spot.coordinates, liveWind: _liveWind),
              const SizedBox(height: 20),
              // Satellite Visibility (collapsed label, expandable details)
              if (spot.satelliteReadings != null && spot.satelliteReadings!.hasAnyData) ...[
                _buildSectionHeader('VISIBILITY'),
                const SizedBox(height: 10),
                SatelliteReadingsCard(readings: spot.satelliteReadings),
                const SizedBox(height: 20),
              ],
              // Loading indicator for remaining data
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: _cardColor,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: _borderColor),
                ),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: AppColors.info,
                      ),
                    ),
                    SizedBox(width: 10),
                    Text(
                      'Loading forecast & details...',
                      style: TextStyle(color: AppColors.darkTextMuted, fontSize: 12),
                    ),
                  ],
                ),
              ),
            ],
          ),
          // Forecast tab - loading
          const Center(
            child: CircularProgressIndicator(
              strokeWidth: 2,
              color: AppColors.info,
            ),
          ),
          ListView(
            padding: const EdgeInsets.all(16),
            children: const [],
          ),
        ],
      ),
    );
  }

  Widget _buildPreloadedHeader(SpotSummary spot) {
    final safeTop = MediaQuery.of(context).padding.top;
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [AppColors.oceanBlue, _bgColor],
        ),
      ),
      padding: EdgeInsets.fromLTRB(8, safeTop, 20, 48),
      alignment: Alignment.centerLeft,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          IconButton(
            onPressed: () => context.pop(),
            icon: const Icon(Icons.arrow_back, color: Colors.white),
          ),
          Expanded(
            child: Text(
              spot.name,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 22,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          const SizedBox(width: 12),
          ScoreTierPill(score: spot.shakaScore, width: 14, height: 48, vertical: true),
        ],
      ),
    );
  }

  Widget _buildLoadingState() {
    return SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Row(
              children: [
                IconButton(
                  onPressed: () => context.pop(),
                  icon: const Icon(Icons.arrow_back, color: AppColors.darkTextSecondary),
                ),
              ],
            ),
          ),
          const Expanded(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircularProgressIndicator(
                    strokeWidth: 2,
                    color: AppColors.info,
                  ),
                  SizedBox(height: 16),
                  Text(
                    'Loading spot...',
                    style: TextStyle(color: AppColors.darkTextMuted, fontSize: 14),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildError(String message) {
    return SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Row(
              children: [
                IconButton(
                  onPressed: () => context.pop(),
                  icon: const Icon(Icons.arrow_back, color: AppColors.darkTextSecondary),
                ),
              ],
            ),
          ),
          Expanded(
            child: Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 40),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.error_outline,
                        color: AppColors.darkTextHint, size: 48),
                    const SizedBox(height: 16),
                    const Text(
                      'Unable to load',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 18,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      message,
                      style:
                          const TextStyle(color: AppColors.darkTextMuted, fontSize: 14),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 24),
                    TextButton(
                      onPressed: () {
                        HapticFeedback.lightImpact();
                        _hasLoaded = false;
                        _loadSpotDetail();
                      },
                      child: const Text(
                        'Retry',
                        style:
                            TextStyle(color: AppColors.info, fontSize: 15),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  // ============ UTILITIES ============

  Color _getScoreColor(int score) => AppColors.getScoreColor(score);

  String _formatDate(String date) {
    try {
      final d = DateTime.parse(date);
      return '${d.month}/${d.day}';
    } catch (e) {
      return date;
    }
  }

  String _formatWeekday(String date) {
    try {
      final d = DateTime.parse(date);
      const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
      return days[d.weekday - 1];
    } catch (e) {
      return '';
    }
  }
}
