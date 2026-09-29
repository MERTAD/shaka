import 'dart:async';
import 'dart:math' show Point, sqrt;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:maplibre_gl/maplibre_gl.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import '../../../core/theme/app_colors.dart';
import '../../../data/api/shaka_api_client.dart';
import '../../../data/models/spot_models.dart';
import '../../../data/services/ip_geolocation_service.dart';
import '../../../data/services/map_background_service.dart';
import '../../../data/services/map_home_service.dart';
import '../../../data/services/unit_preference_service.dart';
import '../../../core/utils/unit_converter.dart';
import '../../../core/utils/wind_format.dart';
import '../../widgets/background_picker.dart';
import '../../widgets/live_wind_value.dart';
import '../../utils/tier_pill_painter.dart';
import '../../widgets/score_tier_pill.dart';
import '../../widgets/set_map_home_dialog.dart';
import '../../widgets/save_spot_sheet.dart';
import '../results/pfz_zones_screen.dart';

/// Full-screen explore map for discovering dive spots.
/// Surfline-style: Map 70% top, horizontal spot carousel 30% bottom.
/// Now using MapLibre for consistent map experience across the app.
class ExploreScreen extends StatefulWidget {
  const ExploreScreen({super.key});

  /// Notifies MainShell to hide/show bottom nav during pin mode.
  static final pinModeActive = ValueNotifier<bool>(false);

  @override
  State<ExploreScreen> createState() => _ExploreScreenState();
}

class _ExploreScreenState extends State<ExploreScreen> {
  MaplibreMapController? _mapController;
  final ShakaApiClient _apiClient = ShakaApiClient();
  final PageController _carouselController = PageController(viewportFraction: 0.85);
  final MapBackgroundService _bgService = MapBackgroundService();
  final IpGeolocationService _ipGeoService = IpGeolocationService();
  
  // Default fallback (Hawaii) - used if Map Home and IP geolocation not available
  static const _fallbackCenter = LatLng(21.3069, -157.8583);
  static const _defaultZoom = 8.5; // ~30 mile radius when not using Map Home
  // When using Map Home: ~200 km radius view
  static final _mapHomeZoom = MapHomeService.mapHomeZoom;
  
  // Actual default center (set in initState from Map Home, IP geolocation, or fallback)
  LatLng _defaultCenter = _fallbackCenter;
  double _initialZoom = _defaultZoom;

  /// When false, we have not yet resolved initial center (getMapHome); map is not built so we avoid cold-start animateCamera crash.
  bool _initialCenterReady = false;

  // Combined spots: curated + user saved spots (rebuilt when either changes)
  List<SpotMapMarker> _allSpots = [];
  
  // NEW: Spots filtered to current viewport (for carousel)
  List<SpotMapMarker> _visibleSpots = [];
  
  bool _isLoading = true;
  bool _isMapReady = false;
  /// True only after spots are loaded, markers added, and carousel updated.
  bool _mapFullyReady = false;
  /// True during style transitions — drives the crossfade overlay.
  bool _styleTransitionActive = false;
  String? _error;
  int? _selectedSpotIndex;
  
  // Debounce timer for map animations
  Timer? _mapAnimationDebounce;
  bool _isFromMarkerTap = false;
  
  // Badge image tracking: _badgeImageCache holds generated PNG bytes (survives
  // style changes), _registeredBadgeImages tracks what's uploaded to the current
  // GL context (cleared on style change so bytes are re-uploaded).
  final Map<String, Uint8List> _badgeImageCache = {};
  final Set<String> _registeredBadgeImages = {};
  
  // Debounce timer for viewport filtering (no API calls, just client-side filter)
  Timer? _viewportFilterDebounce;
  
  // Key to force map rebuild when background changes
  int _mapKey = 0;
  
  // Style version to prevent race conditions during rapid style changes
  int _styleVersion = 0;
  
  // Load version to prevent race conditions between spot loads
  int _loadVersion = 0;
  
  // Store camera position to restore after style change
  CameraPosition? _lastCameraPosition;
  
  // Track pointer for tap detection (to distinguish taps from pans)
  Offset? _pointerDownPosition;
  DateTime? _pointerDownTime;
  
  // Saved spots state
  List<SpotMapMarker> _curatedSpots = [];  // From /spots/all
  List<UserSpotResponse> _userSpots = [];  // From /user-spots
  bool _showMySpots = true;    // Toggle user spots on map
  bool _showAllSpots = true;   // Toggle curated/default spots on map
  
  // Pin mode state
  bool _isPinMode = false;
  LatLng? _currentCenter;
  
  // Pulse animation for loading user spots (no score yet)
  Timer? _spotPulseTimer;
  bool _pulseExpanded = false;
  
  // Suppress onPageChanged during programmatic carousel animations
  bool _isProgrammaticScroll = false;

  @override
  void initState() {
    super.initState();
    _bgService.addListener(_onBackgroundChanged);
    MapHomeService.mapHomeChanged.addListener(_onMapHomeChanged);
    _initDefaultCenter();
    _preGenerateChipImages();
    // NOTE: All spots are loaded once in _onStyleLoaded - no location-based reload
    _loadSavedSpots();
  }

  /// When user sets Map Home from Profile, animate to new center (map is already up and ready).
  void _onMapHomeChanged() {
    if (!mounted) return;
    MapHomeService().getMapHome().then((home) {
      if (home != null && _mapController != null && mounted && _isMapReady) {
        _defaultCenter = LatLng(home.lat, home.lon);
        _mapController!.animateCamera(
          CameraUpdate.newLatLngZoom(LatLng(home.lat, home.lon), _mapHomeZoom),
        );
        debugPrint('ExploreScreen: Moved to Map Home (${home.lat}, ${home.lon})');
      }
    });
  }

  /// Resolve initial center (Map Home, IP, or fallback) then set _initialCenterReady so the map is built once with correct initialCameraPosition (avoids cold-start animateCamera crash).
  Future<void> _initDefaultCenter() async {
    final mapHome = await MapHomeService().getMapHome();
    if (mapHome != null) {
      _defaultCenter = LatLng(mapHome.lat, mapHome.lon);
      _initialZoom = _mapHomeZoom;
      debugPrint('ExploreScreen: Using Map Home (${mapHome.lat}, ${mapHome.lon})');
    } else {
      final ipLocation = _ipGeoService.location;
      if (ipLocation != null) {
        _defaultCenter = LatLng(ipLocation.lat, ipLocation.lon);
        _initialZoom = _defaultZoom;
        debugPrint('ExploreScreen: Using IP location ${ipLocation.city ?? "unknown"}');
      } else {
        _defaultCenter = _fallbackCenter;
        _initialZoom = _defaultZoom;
        debugPrint('ExploreScreen: Using fallback location (Hawaii) - listening for IP');
        _ipGeoService.addListener(_onIpLocationChanged);
      }
    }
    if (mounted) {
      setState(() => _initialCenterReady = true);
    }
    if (mapHome == null && mounted) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!mounted) return;
        await SetMapHomeDialog.showIfNeeded(context);
      });
    }
  }
  
  /// Try to animate map to IP location - called from both IP callback and style loaded
  /// Returns true if animation was performed (only when not using Map Home)
  /// NOTE: No longer reloads spots - all spots are loaded once globally
  bool _tryAnimateToIpLocation() {
    final ipLocation = _ipGeoService.location;
    
    // Need: IP available, still at fallback (no Map Home), map ready
    if (ipLocation != null &&
        _defaultCenter == _fallbackCenter &&
        _mapController != null &&
        _isMapReady) {
      debugPrint('ExploreScreen: Animating to IP location - ${ipLocation.city ?? "unknown"}');
      _defaultCenter = LatLng(ipLocation.lat, ipLocation.lon);
      _initialZoom = _defaultZoom;

      _mapController!.animateCamera(
        CameraUpdate.newLatLngZoom(_defaultCenter, _initialZoom),
      );
      // After animation settles, _onCameraIdle will update _visibleSpots

      // Remove listener - we've successfully animated
      _ipGeoService.removeListener(_onIpLocationChanged);
      return true;
    }
    return false;
  }
  
  /// Called when IP geolocation becomes available after initial load
  void _onIpLocationChanged() {
    _tryAnimateToIpLocation();
  }
  
  @override
  void dispose() {
    _mapAnimationDebounce?.cancel();
    _viewportFilterDebounce?.cancel();
    _spotPulseTimer?.cancel();
    _carouselController.dispose();
    _bgService.removeListener(_onBackgroundChanged);
    MapHomeService.mapHomeChanged.removeListener(_onMapHomeChanged);
    _ipGeoService.removeListener(_onIpLocationChanged);
    ExploreScreen.pinModeActive.value = false;
    _mapController = null;
    super.dispose();
  }
  
  void _onBackgroundChanged() {
    // BLOCK: Don't allow style change while map is loading (prevents iOS crash)
    if (!_isMapReady) {
      debugPrint('Explore: Ignoring style change - map not ready');
      return;
    }
    
    // Save current camera position before rebuilding map
    if (_mapController != null) {
      _lastCameraPosition = _mapController!.cameraPosition;
    }
    
    // Show crossfade overlay BEFORE destroying the map
    _mapController = null;
    _isMapReady = false;
    _mapFullyReady = false;
    _styleTransitionActive = true;
    
    // Increment style version to cancel any in-progress async operations
    _styleVersion++;
    
    // Style rebuild destroys the GL context — registered images are gone
    _registeredBadgeImages.clear();
    
    // Increment key to force MapLibreMap widget rebuild with new style
    setState(() {
      _mapKey++;
      _error = null;
    });
  }
  
  void _onMapCreated(MaplibreMapController controller) {
    _mapController = controller;
  }

  void _onStyleLoaded() async {
    final currentVersion = _styleVersion;
    debugPrint('_onStyleLoaded: Style loaded for ${_bgService.current}');
    
    _tryAnimateToIpLocation();
    
    // Load curated spots once (if not already loaded)
    if (_curatedSpots.isEmpty) {
      await _loadAllSpots();
    }
    
    // Add overlays (raster tile layers) — these render underneath markers
    await _addOverlays();
    if (!mounted || _styleVersion != currentVersion) return;
    
    // Mark map ready immediately — no delay needed for the overlay itself.
    // Raster tiles stream in progressively; markers go on top.
    if (mounted) setState(() => _isMapReady = true);

    // Small yield so the GL context finishes registering sources
    await Future.delayed(const Duration(milliseconds: 50));
    if (!mounted || _styleVersion != currentVersion) return;
    
    // Add markers on top
    await _updateMarkers();
    await _updateVisibleSpots();
    
    // Everything ready — begin fading out the crossfade overlay
    if (mounted) {
      setState(() {
        _mapFullyReady = true;
        _styleTransitionActive = false;
      });
    }
    debugPrint('_onStyleLoaded: Complete for ${_bgService.current}');
  }

  /// Add raster overlays based on current background
  Future<void> _addOverlays() async {
    if (_mapController == null) return;
    
    final overlays = _bgService.getOverlays(_bgService.current);
    debugPrint('Adding ${overlays.length} overlays for ${_bgService.current}');
    
    for (final overlay in overlays) {
      // Bail out if controller was nulled (style change in progress)
      if (_mapController == null) return;
      
      try {
        // Remove existing source/layer if present (use safe ?. operator)
        try {
          await _mapController?.removeLayer('${overlay.id}-layer');
          await _mapController?.removeSource(overlay.id);
        } catch (_) {}
        
        // Check again after awaits
        if (_mapController == null) return;
        
        debugPrint('Adding overlay: ${overlay.id} from ${overlay.urlTemplate}');
        
        // Add raster source
        await _mapController!.addSource(
          overlay.id,
          RasterSourceProperties(
            tiles: [overlay.urlTemplate],
            tileSize: overlay.tileSize.toDouble(),
            minzoom: overlay.minZoom,
            maxzoom: overlay.maxZoom,
          ),
        );
        
        // Check after await
        if (_mapController == null) return;
        
        // Add raster layer
        await _mapController!.addRasterLayer(
          overlay.id,
          '${overlay.id}-layer',
          RasterLayerProperties(
            rasterOpacity: overlay.opacity,
          ),
        );
        
        debugPrint('Successfully added overlay: ${overlay.id}');
      } catch (e) {
        debugPrint('Failed to add overlay ${overlay.id}: $e');
      }
    }
  }
  
  /// Handle map camera changes - filter visible spots for carousel (NO API reload)
  void _onCameraIdle() {
    if (_mapController == null || !_isMapReady) return;
    
    // Debounce viewport filtering for smoother UX
    _viewportFilterDebounce?.cancel();
    _viewportFilterDebounce = Timer(const Duration(milliseconds: 300), () async {
      if (!mounted || _mapController == null) return;
      await _updateVisibleSpots();
    });
  }
  
  /// Filter all spots to those visible in current viewport (client-side, no API)
  Future<void> _updateVisibleSpots() async {
    if (_mapController == null || _allSpots.isEmpty) return;
    
    try {
      // Get current visible bounds
      final bounds = await _mapController!.getVisibleRegion();
      
      // Filter spots to those within bounds
      final visible = _allSpots.where((spot) {
        final lat = spot.coordinates.lat;
        final lon = spot.coordinates.lon;
        return lat >= bounds.southwest.latitude &&
               lat <= bounds.northeast.latitude &&
               lon >= bounds.southwest.longitude &&
               lon <= bounds.northeast.longitude;
      }).toList();
      
      // Sort by score (best first) for carousel
      visible.sort((a, b) => (b.shakaScore ?? 0).compareTo(a.shakaScore ?? 0));
      
      if (mounted) {
        // Preserve current selection if the spot is still in the new viewport
        String? selectedId;
        if (_selectedSpotIndex != null && _selectedSpotIndex! < _visibleSpots.length) {
          selectedId = _visibleSpots[_selectedSpotIndex!].id;
        }

        int newIndex = 0;
        if (selectedId != null && visible.isNotEmpty) {
          final idx = visible.indexWhere((s) => s.id == selectedId);
          if (idx >= 0) newIndex = idx;
        }

        setState(() {
          _visibleSpots = visible;
          _selectedSpotIndex = visible.isNotEmpty ? newIndex : null;
        });

        // Sync carousel to match selection (no-op if already there)
        if (visible.isNotEmpty && _carouselController.hasClients) {
          final currentPage = _carouselController.page?.round() ?? -1;
          if (currentPage != newIndex) {
            _carouselController.jumpToPage(newIndex);
          }
        }

        // Highlight selected spot on map
        _updateSelectedMarker();
      }
      
      debugPrint('🗺️ Viewport: ${visible.length}/${_allSpots.length} spots visible');
    } catch (e) {
      debugPrint('Error updating visible spots: $e');
    }
  }
  
  /// Load ALL spots once from the server (lightweight data for map markers)
  Future<void> _loadAllSpots({int retryCount = 0, int? version}) async {
    // Increment load version to cancel any in-flight requests
    final currentVersion = version ?? ++_loadVersion;
    
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      debugPrint('🗺️ Loading ALL spots for map (v$currentVersion, attempt ${retryCount + 1})');
      final response = await _apiClient.getAllSpots();
      
      // Only apply results if this is still the current load
      if (mounted && currentVersion == _loadVersion) {
        debugPrint('✅ Loaded ${response.count} spots globally (v$currentVersion)');
        setState(() {
          _curatedSpots = response.spots;
          _rebuildCombinedSpots();
          _isLoading = false;
        });
        
        if (_isMapReady) {
          await _updateMarkers();
        }
      } else {
        debugPrint('⏭️ Ignoring stale load (v$currentVersion, current is v$_loadVersion)');
      }
    } catch (e) {
      debugPrint('❌ Failed to load all spots (v$currentVersion, attempt ${retryCount + 1}): $e');
      // Retry up to 3 times with exponential backoff, but only if still current
      if (retryCount < 3 && mounted && currentVersion == _loadVersion) {
        final delay = Duration(milliseconds: 500 * (retryCount + 1));
        await Future.delayed(delay);
        return _loadAllSpots(retryCount: retryCount + 1, version: currentVersion);
      }
      
      if (mounted && currentVersion == _loadVersion) {
        setState(() {
          _error = 'Could not load spots. Tap to retry.';
          _isLoading = false;
        });
      }
    }
  }
  
  // ===========================================
  // SAVED SPOTS + PIN MODE
  // ===========================================

  /// Rebuild the combined spots list (curated + user saved spots)
  void _rebuildCombinedSpots() {
    _allSpots = [
      if (_showAllSpots) ..._curatedSpots,
      if (_showMySpots) ..._userSpots.map((s) => s.toSpotMapMarker()),
    ];
  }

  /// Load user's saved spots from API
  Future<void> _loadSavedSpots() async {
    debugPrint('📍 Explore: Loading saved spots...');
    try {
      final response = await _apiClient.getUserSpots();
      debugPrint('📍 Explore: Got ${response.spots.length} user spots');
      if (mounted) {
        setState(() {
          _userSpots = response.spots;
          _rebuildCombinedSpots();
        });
        // Update markers if map is ready
        if (_isMapReady) {
          await _updateMarkers();
          await _updateVisibleSpots();
        }
        // Fetch scores for spots that don't have them
        _fetchMissingScores();
      }
    } catch (e) {
      debugPrint('📍 Explore: FAILED to load saved spots: $e');
    }
  }

  /// Fetch full detail for user spots missing score or conditions.
  /// Retries up to 3 times with backoff for spots that fail to fetch.
  /// Backoff intervals (seconds) between retry attempts.
  /// First attempt fires after a 3s initial delay to let the server-side
  /// background prefetch populate tide + weather + swell (~5-7s total).
  static const _pollBackoffSeconds = [2, 3, 5, 8];

  Future<void> _fetchMissingScores() async {
    final spotsNeedingData = _userSpots.where(
      (s) => s.shakaScore == null || s.swell == null,
    ).toList();
    if (spotsNeedingData.isEmpty) {
      debugPrint('📍 Explore: All user spots have scores + conditions');
      _stopPulseTimer();
      return;
    }

    if (_showMySpots) _startPulseTimer();

    // Give the server-side background prefetch a head start before first poll
    await Future.delayed(const Duration(seconds: 3));
    if (!mounted) return;

    final today = DateFormat('yyyy-MM-dd').format(DateTime.now());
    final maxAttempts = _pollBackoffSeconds.length + 1;

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      if (!mounted) return;
      final isLastAttempt = attempt == maxAttempts - 1;

      final pending = _userSpots.where(
        (s) => s.shakaScore == null || s.swell == null,
      ).toList();
      if (pending.isEmpty) break;

      if (attempt > 0) {
        final delaySec = _pollBackoffSeconds[attempt - 1];
        debugPrint('📍 Explore: Retry ${attempt + 1}/$maxAttempts for ${pending.length} spots (wait ${delaySec}s)...');
        await Future.delayed(Duration(seconds: delaySec));
        if (!mounted) return;
      } else {
        debugPrint('📍 Explore: Fetching data for ${pending.length} spots...');
      }

      bool anyUpdated = false;
      final futures = <Future>[];
      for (final spot in pending) {
        futures.add(_fetchSpotDetail(spot.id, today).then((detail) {
          if (detail != null) {
            // The detail endpoint responds immediately after creation with
            // placeholder conditions ("Unavailable") while the server-side
            // prefetch is still populating the cache. Applying those would
            // clear the card's loading state with default values, so keep
            // polling until real data arrives. On the last attempt apply
            // whatever we got so the card doesn't spin forever.
            final hasRealData = detail.spot.conditions.swellHeightFt != null;
            if (!hasRealData && !isLastAttempt) {
              debugPrint('📍 Explore: ${spot.id} conditions not ready yet, will retry');
              return;
            }
            final index = _userSpots.indexWhere((s) => s.id == spot.id);
            if (index != -1) {
              final cond = detail.spot.conditions;
              _userSpots[index] = UserSpotResponse(
                id: spot.id,
                name: spot.name,
                coordinates: spot.coordinates,
                region: spot.region,
                country: spot.country,
                createdAt: spot.createdAt,
                isUserSpot: spot.isUserSpot,
                shakaScore: detail.spot.score.overall,
                visibility: cond.visibility,
                swell: cond.swellCorrected ?? cond.swell,
                wind: cond.wind,
                waterTemp: cond.waterTemp,
              );
              anyUpdated = true;
            }
          }
        }));
      }

      await Future.wait(futures);

      if (anyUpdated && mounted && _showMySpots) {
        debugPrint('📍 Explore: Updated spot data, refreshing markers');
        setState(() => _rebuildCombinedSpots());
        await _updateMarkers();
        await _updateVisibleSpots();
      }
    }

    if (_userSpots.every((s) => s.shakaScore != null && s.swell != null)) {
      _stopPulseTimer();
    }
  }

  Future<UserSpotDetailResponse?> _fetchSpotDetail(String spotId, String date) async {
    try {
      return await _apiClient.getUserSpotDetail(spotId: spotId, date: date);
    } catch (e) {
      debugPrint('📍 Explore: Failed to fetch detail for $spotId: $e');
      return null;
    }
  }

  // Pulse timer for loading user spots
  void _startPulseTimer() {
    if (_spotPulseTimer != null) return;
    _pulseExpanded = false;
    _spotPulseTimer = Timer.periodic(const Duration(milliseconds: 800), (_) {
      _pulseExpanded = !_pulseExpanded;
      _updatePulseRing();
    });
    _updatePulseRing();
  }

  void _stopPulseTimer() {
    _spotPulseTimer?.cancel();
    _spotPulseTimer = null;
    _removePulseRing();
  }

  Future<void> _removePulseRing() async {
    try { await _mapController?.removeLayer('user-spots-pulse-layer'); } catch (_) {}
    try { await _mapController?.removeSource('user-spots-pulse-source'); } catch (_) {}
  }

  Future<void> _updatePulseRing() async {
    if (_mapController == null) return;

    final loadingSpots = _userSpots.where((s) => s.shakaScore == null || s.swell == null).toList();
    if (loadingSpots.isEmpty) {
      _stopPulseTimer();
      return;
    }

    await _removePulseRing();
    if (_mapController == null) return;

    final radius = _pulseExpanded ? 19.2 : 12.8;
    final opacity = _pulseExpanded ? 0.15 : 0.35;

    final features = loadingSpots.map((spot) => {
      'type': 'Feature',
      'geometry': {
        'type': 'Point',
        'coordinates': [spot.longitude, spot.latitude],
      },
      'properties': {
        'radius': radius,
        'opacity': opacity,
      },
    }).toList();

    try {
      await _mapController!.addSource(
        'user-spots-pulse-source',
        GeojsonSourceProperties(data: {
          'type': 'FeatureCollection',
          'features': features,
        }),
      );

      if (_mapController == null) return;

      await _mapController!.addCircleLayer(
        'user-spots-pulse-source',
        'user-spots-pulse-layer',
        CircleLayerProperties(
          circleRadius: ['get', 'radius'],
          circleColor: '#7A9BB8',
          circleOpacity: ['get', 'opacity'],
          circleStrokeWidth: 0,
        ),
        belowLayerId: 'spots-layer',
      );
    } catch (e) {
      debugPrint('📍 Explore: Pulse ring update failed: $e');
    }
  }

  // Pin mode methods
  void _enterPinMode() {
    final center = _mapController?.cameraPosition?.target;
    // Preserve current camera so leaving pin mode doesn't "reset" view.
    if (_mapController != null) {
      _lastCameraPosition = _mapController!.cameraPosition;
    }
    setState(() {
      _isPinMode = true;
      _currentCenter = center;
    });
    ExploreScreen.pinModeActive.value = true;
    HapticFeedback.mediumImpact();
  }

  void _exitPinMode() {
    // Preserve current camera so layout changes don't force a zoom-out.
    if (_mapController != null) {
      _lastCameraPosition = _mapController!.cameraPosition;
    }
    setState(() {
      _isPinMode = false;
      _currentCenter = null;
    });
    ExploreScreen.pinModeActive.value = false;
  }

  Future<void> _confirmPinLocation() async {
    if (_currentCenter == null) return;

    final saved = await SaveSpotSheet.show(
      context: context,
      latitude: _currentCenter!.latitude,
      longitude: _currentCenter!.longitude,
    );

    if (saved && mounted) {
      _exitPinMode();
      await _loadSavedSpots();
    }
  }

  void _onCameraMove() {
    if (_isPinMode && _mapController != null) {
      final position = _mapController!.cameraPosition;
      if (position != null) {
        setState(() {
          _currentCenter = position.target;
        });
      }
    }
  }

  void _showSavedSpotsSheet() {
    bool infoExpanded = false;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => DraggableScrollableSheet(
        initialChildSize: 0.6,
        minChildSize: 0.3,
        maxChildSize: 0.9,
        builder: (context, scrollController) {
          return StatefulBuilder(
            builder: (context, setModalState) {
              void toggleMySpots() {
                setModalState(() => _showMySpots = !_showMySpots);
                setState(() => _rebuildCombinedSpots());
                _updateMarkers();
                _updateVisibleSpots();
              }
              void toggleAllSpots() {
                setModalState(() => _showAllSpots = !_showAllSpots);
                setState(() => _rebuildCombinedSpots());
                _updateMarkers();
                _updateVisibleSpots();
              }

              return Container(
                decoration: const BoxDecoration(
                  color: AppColors.darkSurface,
                  borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
                ),
                child: Column(
                  children: [
                    Container(
                      margin: const EdgeInsets.only(top: 12),
                      width: 40,
                      height: 4,
                      decoration: BoxDecoration(
                        color: AppColors.darkTextHint,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
                      child: Row(
                        children: [
                          const Text(
                            'My Spots',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 18,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const Spacer(),
                          GestureDetector(
                            onTap: () => setModalState(() => infoExpanded = !infoExpanded),
                            child: Row(
                              children: [
                                Text(
                                  'Spot Privacy',
                                  style: TextStyle(
                                    color: infoExpanded ? AppColors.info : AppColors.darkTextHint,
                                    fontSize: 13,
                                  ),
                                ),
                                const SizedBox(width: 4),
                                Icon(
                                  Icons.info_outline,
                                  color: infoExpanded ? AppColors.info : AppColors.darkTextHint,
                                  size: 18,
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                    // Map display toggles (only when user has spots)
                    if (_userSpots.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: Row(
                        children: [
                          Expanded(
                            child: GestureDetector(
                              onTap: toggleMySpots,
                              child: Container(
                                padding: const EdgeInsets.symmetric(vertical: 8),
                                decoration: BoxDecoration(
                                  color: _showMySpots
                                      ? AppColors.info.withOpacity(0.15)
                                      : Colors.white.withOpacity(0.05),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: _showMySpots
                                        ? AppColors.info.withOpacity(0.4)
                                        : Colors.white.withOpacity(0.1),
                                  ),
                                ),
                                child: Row(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Icon(
                                      _showMySpots ? Icons.check_circle : Icons.circle_outlined,
                                      color: _showMySpots ? AppColors.info : AppColors.darkTextHint,
                                      size: 16,
                                    ),
                                    const SizedBox(width: 6),
                                    Text(
                                      'My Spots',
                                      style: TextStyle(
                                        color: _showMySpots ? AppColors.info : AppColors.darkTextMuted,
                                        fontSize: 13,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: GestureDetector(
                              onTap: toggleAllSpots,
                              child: Container(
                                padding: const EdgeInsets.symmetric(vertical: 8),
                                decoration: BoxDecoration(
                                  color: _showAllSpots
                                      ? AppColors.info.withOpacity(0.15)
                                      : Colors.white.withOpacity(0.05),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: _showAllSpots
                                        ? AppColors.info.withOpacity(0.4)
                                        : Colors.white.withOpacity(0.1),
                                  ),
                                ),
                                child: Row(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Icon(
                                      _showAllSpots ? Icons.check_circle : Icons.circle_outlined,
                                      color: _showAllSpots ? AppColors.info : AppColors.darkTextHint,
                                      size: 16,
                                    ),
                                    const SizedBox(width: 6),
                                    Text(
                                      'All Spots',
                                      style: TextStyle(
                                        color: _showAllSpots ? AppColors.info : AppColors.darkTextMuted,
                                        fontSize: 13,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    // Info disclaimer (collapsible)
                    AnimatedCrossFade(
                      firstChild: const SizedBox.shrink(),
                      secondChild: Container(
                        margin: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.05),
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: Colors.white.withOpacity(0.08)),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Icon(Icons.lock_outline, color: AppColors.info, size: 16),
                                const SizedBox(width: 8),
                                const Text(
                                  'Your spots are private',
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            const Text(
                              'Your saved spots are private — only visible to you, never shown to other people. We don\'t sell or share them, and you can delete a spot at any time.',
                              style: TextStyle(color: AppColors.darkTextMuted, fontSize: 12, height: 1.4),
                            ),
                          ],
                        ),
                      ),
                      crossFadeState: infoExpanded ? CrossFadeState.showSecond : CrossFadeState.showFirst,
                      duration: const Duration(milliseconds: 200),
                    ),
                    const SizedBox(height: 12),
                    // Spot list
                    Expanded(
                      child: _userSpots.isEmpty
                          ? const Center(
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(Icons.bookmark_border, color: AppColors.darkTextHint, size: 48),
                                  SizedBox(height: 12),
                                  Text(
                                    'No spots yet',
                                    style: TextStyle(color: AppColors.darkTextMuted),
                                  ),
                                  SizedBox(height: 4),
                                  Text(
                                    'Tap the + button to save a location',
                                    style: TextStyle(color: AppColors.darkTextHint, fontSize: 12),
                                  ),
                                ],
                              ),
                            )
                          : ListView.builder(
                              controller: scrollController,
                              padding: const EdgeInsets.symmetric(horizontal: 16),
                              itemCount: _userSpots.length,
                              itemBuilder: (context, index) {
                                final spot = _userSpots[index];
                                final isLoading = spot.shakaScore == null || spot.swell == null;
                                return _SavedSpotCard(
                                  spot: spot,
                                  isLoading: isLoading,
                                  onTap: isLoading ? null : () {
                                    Navigator.pop(context);
                                    _navigateToUserSpotDetail(spot);
                                  },
                                  onDelete: () async {
                                    await _deleteSpot(spot, setModalState);
                                  },
                                );
                              },
                            ),
                    ),
                  ],
                ),
              );
            },
          );
        },
      ),
    );
  }

  void _navigateToUserSpotDetail(UserSpotResponse spot) {
    final today = DateFormat('yyyy-MM-dd').format(DateTime.now());
    context.push('/spot/${spot.id}', extra: {
      'date': today,
      'isUserSpot': true,
    }).then((_) {
      if (mounted) _loadSavedSpots();
    });
  }

  Future<void> _deleteSpot(UserSpotResponse spot, StateSetter setModalState) async {
    try {
      await _apiClient.deleteUserSpot(spot.id);
      setModalState(() {
        _userSpots.removeWhere((s) => s.id == spot.id);
      });
      setState(() => _rebuildCombinedSpots());
      await _updateMarkers();
      await _updateVisibleSpots();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Deleted "${spot.name}"'),
            backgroundColor: AppColors.warning,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('Failed to delete spot'),
            backgroundColor: AppColors.error,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Map marker image generation (Surfline-style colored chips)
  // ---------------------------------------------------------------------------

  Future<void> _preGenerateChipImages() async {
    await Future.wait(tierDefs.entries.map((entry) async {
      final key = 'chip-${entry.key}';
      final label = tierLabels[entry.key] ?? '—';
      _badgeImageCache[key] ??=
          await generateScoreChipImage(entry.key, entry.value, label);

      final selectedKey = 'selected-chip-${entry.key}';
      _badgeImageCache[selectedKey] ??=
          await generateSelectedShakaImage(entry.key, entry.value);
    }));
  }

  Future<void> _registerScoreBadgeImages() async {
    if (_mapController == null) return;
    if (_badgeImageCache.length < 12) await _preGenerateChipImages();

    await Future.wait(tierDefs.keys.map((tier) async {
      for (final prefix in ['chip', 'selected-chip']) {
        final key = '$prefix-$tier';
        if (_registeredBadgeImages.contains(key)) return;
        if (_mapController == null) return;
        await _mapController!.addImage(key, _badgeImageCache[key]!);
        _registeredBadgeImages.add(key);
      }
    }));
  }

  /// Update markers using GeoJSON layer - renders ON TOP of raster overlays
  /// Shows ALL spots (not filtered) - markers persist during pan/zoom
  Future<void> _updateMarkers() async {
    if (_mapController == null || !_isMapReady) return;

    final spots = _allSpots;

    // Remove old layers & source
    for (final id in ['spots-labels', 'spots-layer']) {
      try { await _mapController?.removeLayer(id); } catch (_) {}
    }
    try { await _mapController?.removeSource('spots-source'); } catch (_) {}
    if (_mapController == null || spots.isEmpty) return;

    // Ensure badge icons are registered for every score in the data
    await _registerScoreBadgeImages();
    if (_mapController == null) return;

    // Build GeoJSON — each feature carries its tier pill icon
    final features = spots.map((spot) {
      final score = spot.shakaScore ?? 0;
      final tierKey = chipKeyForScore(spot.shakaScore);
      return {
        'type': 'Feature',
        'properties': {
          'id': spot.id,
          'name': spot.name,
          'icon': tierKey,
          'sortKey': -score,
        },
        'geometry': {
          'type': 'Point',
          'coordinates': [spot.coordinates.lon, spot.coordinates.lat],
        },
      };
    }).toList();

    try {
      await _mapController!.addSource(
        'spots-source',
        GeojsonSourceProperties(data: {
          'type': 'FeatureCollection',
          'features': features,
        }),
      );
      if (_mapController == null) return;

      await _mapController!.addSymbolLayer(
        'spots-source',
        'spots-layer',
        const SymbolLayerProperties(
          iconImage: ['get', 'icon'],
          iconSize: 0.8,
          iconAllowOverlap: true,
          iconIgnorePlacement: true,
          symbolSortKey: ['get', 'sortKey'],
        ),
      );

      debugPrint('🗺️ Rendered ${spots.length} chip markers');
    } catch (e) {
      debugPrint('Failed to add spots layer: $e');
    }
  }
  
  /// Highlight the selected spot with a shaka silhouette marker.
  Future<void> _updateSelectedMarker() async {
    if (_mapController == null) return;

    for (final id in ['selected-spot-icon']) {
      try { await _mapController?.removeLayer(id); } catch (_) {}
    }
    try { await _mapController?.removeSource('selected-spot-source'); } catch (_) {}

    if (_mapController == null) return;
    if (_selectedSpotIndex == null || _selectedSpotIndex! >= _visibleSpots.length) return;

    final spot = _visibleSpots[_selectedSpotIndex!];
    final shakaKey = selectedChipKeyForScore(spot.shakaScore);

    final geojson = {
      'type': 'FeatureCollection',
      'features': [
        {
          'type': 'Feature',
          'geometry': {
            'type': 'Point',
            'coordinates': [spot.coordinates.lon, spot.coordinates.lat],
          },
          'properties': {
            'icon': shakaKey,
          },
        },
      ],
    };

    try {
      await _mapController!.addSource(
        'selected-spot-source',
        GeojsonSourceProperties(data: geojson),
      );
      if (_mapController == null) return;

      await _mapController!.addSymbolLayer(
        'selected-spot-source',
        'selected-spot-icon',
        const SymbolLayerProperties(
          iconImage: ['get', 'icon'],
          iconSize: 1.0,
          iconAllowOverlap: true,
          iconIgnorePlacement: true,
        ),
      );
    } catch (e) {
      debugPrint('Failed to update selected marker: $e');
    }
  }

  void _onSpotSelected(int index) {
    if (index == _selectedSpotIndex) return;
    
    // Ignore intermediate pages fired during programmatic carousel animation
    if (_isProgrammaticScroll) return;
    
    // Check marker tap flag BEFORE haptic to avoid double-buzz
    if (_isFromMarkerTap) {
      _isFromMarkerTap = false;
      setState(() => _selectedSpotIndex = index);
      _updateSelectedMarker();
      return;
    }
    
    HapticFeedback.selectionClick();
    setState(() => _selectedSpotIndex = index);
    _updateSelectedMarker();
    
    // Animate to selected spot (no markers rebuild needed)
    _mapAnimationDebounce?.cancel();
    _mapAnimationDebounce = Timer(const Duration(milliseconds: 300), () {
      if (!mounted || _mapController == null) return;
      if (index < _visibleSpots.length) {
        final spot = _visibleSpots[index];
        _mapController!.animateCamera(
          CameraUpdate.newLatLngZoom(
            LatLng(spot.coordinates.lat, spot.coordinates.lon),
            10.0,
          ),
        );
      }
    });
  }
  
  /// Handle map taps - find tapped spot and select it in carousel
  Future<void> _handleMapTap(Offset screenPoint) async {
    if (_mapController == null || !_isMapReady) return;
    
    final devicePixelRatio = MediaQuery.of(context).devicePixelRatio;
    
    // Try BOTH coordinate interpretations to find which one works
    final tapLogical = screenPoint;
    final tapPhysical = screenPoint * devicePixelRatio;
    
    if (_allSpots.isEmpty) return;
    
    // Find closest spot from ALL spots (markers are always visible)
    SpotMapMarker? closestSpot;
    double closestDist = double.infinity;
    
    for (final spot in _allSpots) {
      // Bail out if controller was nulled (style change)
      if (_mapController == null) return;
      
      final spotLatLng = LatLng(spot.coordinates.lat, spot.coordinates.lon);
      final spotScreenPos = await _mapController!.toScreenLocation(spotLatLng);
      
      // Check again after await
      if (_mapController == null) return;
      
      // Try both coordinate systems and use the closer match
      final dxL = tapLogical.dx - spotScreenPos.x;
      final dyL = tapLogical.dy - spotScreenPos.y;
      final distLogical = sqrt(dxL * dxL + dyL * dyL);
      
      final dxP = tapPhysical.dx - spotScreenPos.x;
      final dyP = tapPhysical.dy - spotScreenPos.y;
      final distPhysical = sqrt(dxP * dxP + dyP * dyP);
      
      final minDist = distLogical < distPhysical ? distLogical : distPhysical;
      
      if (minDist < closestDist) {
        closestDist = minDist;
        closestSpot = spot;
      }
    }
    
    // Use generous threshold to find match
    const threshold = 50.0;
    
    if (closestDist < threshold && closestSpot != null) {
      debugPrint('Marker tap: ${closestSpot.name} (dist ${closestDist.toStringAsFixed(0)}px)');
      _onMarkerTapped(closestSpot);
    }
  }
  
  /// Handle marker tap - navigate to spot (may need to zoom if out of viewport)
  void _onMarkerTapped(SpotMapMarker tappedSpot) {
    HapticFeedback.selectionClick();
    
    // Find spot in visible carousel (if present)
    final visibleIndex = _visibleSpots.indexWhere((s) => s.id == tappedSpot.id);
    
    if (visibleIndex >= 0) {
      // Spot is in carousel - just select it
      _isFromMarkerTap = true;
      setState(() => _selectedSpotIndex = visibleIndex);
      _updateSelectedMarker();
      
      // Suppress onPageChanged during programmatic scroll to avoid cycling highlight
      _isProgrammaticScroll = true;
      _carouselController.animateToPage(
        visibleIndex,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeInOut,
      ).then((_) {
        _isProgrammaticScroll = false;
      });
    } else {
      // Spot is not in current viewport - animate to it
      // After animation, _onCameraIdle will update _visibleSpots and it will appear
      _mapController?.animateCamera(
        CameraUpdate.newLatLngZoom(
          LatLng(tappedSpot.coordinates.lat, tappedSpot.coordinates.lon),
          10.0,
        ),
      );
    }
  }

  /// Open spot detail page - works with either SpotMapMarker or SpotSummary
  void _openSpotDetail(SpotMapMarker spot) {
    // Block navigation for user spots still loading (need score + conditions)
    if (spot.isUserSpot && !_isUserSpotReady(spot)) {
      HapticFeedback.lightImpact();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Still getting intel on this spot...'),
          duration: Duration(seconds: 2),
          behavior: SnackBarBehavior.floating,
        ),
      );
      return;
    }
    final today = DateFormat('yyyy-MM-dd').format(DateTime.now());
    context.push('/spot/${spot.id}', extra: {
      'date': today,
      if (spot.isUserSpot) 'isUserSpot': true,
    }).then((_) {
      // Re-fetch user spots when returning — server cache is now populated
      if (spot.isUserSpot && mounted) {
        _loadSavedSpots();
      }
    });
  }

  /// A user spot is "ready" when we have both the score and condition data.
  bool _isUserSpotReady(SpotMapMarker spot) {
    return spot.shakaScore != null && spot.swell != null;
  }
  
  void _showBackgroundPicker() {
    showBackgroundPicker(context);
  }

  @override
  Widget build(BuildContext context) {
    final screenHeight = MediaQuery.of(context).size.height;
    final topPadding = MediaQuery.of(context).padding.top;
    final bottomPadding = MediaQuery.of(context).padding.bottom;
    // Keep the map widget mounted across pin mode toggles.
    // Using different parent widgets (SizedBox vs Expanded) can cause a remount
    // and MapLibre will fall back to initialCameraPosition (zooming out).
    final mapFlex = _isPinMode ? 1 : 3;
    const carouselHeight = 170.0;

    // Map stack children (shared between pin mode and normal mode)
    final mapChildren = <Widget>[
      // Build map only after initial center is resolved (avoids cold-start animateCamera crash)
      if (_initialCenterReady)
        Listener(
          behavior: HitTestBehavior.translucent,
          onPointerDown: (event) {
            _pointerDownPosition = event.localPosition;
            _pointerDownTime = DateTime.now();
          },
          onPointerUp: (event) {
            final downPos = _pointerDownPosition;
            final downTime = _pointerDownTime;
            _pointerDownPosition = null;
            _pointerDownTime = null;
            if (downPos == null || downTime == null) return;
            final duration = DateTime.now().difference(downTime);
            final distance = (event.localPosition - downPos).distance;
            if (duration.inMilliseconds < 300 && distance < 20) {
              if (!_isPinMode) {
                debugPrint('TAP DETECTED at ${event.localPosition}');
                _handleMapTap(event.localPosition);
              }
            }
          },
          onPointerMove: (event) {
            if (_isPinMode && _mapController != null) {
              final pos = _mapController!.cameraPosition;
              if (pos != null && (_currentCenter == null || 
                  pos.target.latitude != _currentCenter!.latitude ||
                  pos.target.longitude != _currentCenter!.longitude)) {
                setState(() => _currentCenter = pos.target);
              }
            }
          },
          child: MaplibreMap(
            key: ValueKey('map_$_mapKey'),
            onMapCreated: _onMapCreated,
            onStyleLoadedCallback: _onStyleLoaded,
            onCameraIdle: () {
              _onCameraIdle();
              _onCameraMove();
            },
            initialCameraPosition: _lastCameraPosition ?? CameraPosition(
              target: _defaultCenter,
              zoom: _initialZoom,
            ),
            styleString: _bgService.getStyleUrl(_bgService.current),
            minMaxZoomPreference: const MinMaxZoomPreference(3, 18),
            trackCameraPosition: true,
            compassEnabled: false,
            attributionButtonMargins: const Point(-100, -100),
            logoViewMargins: const Point(-100, -100),
          ),
        ),

      // GPS Coordinates Display (centered at top when in pin mode)
      if (_isPinMode && _currentCenter != null)
        Positioned(
          top: topPadding + 8,
          left: 0,
          right: 0,
          child: Center(
            child: Container(
              height: 48,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.7),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.darkTextHint),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.gps_fixed, color: AppColors.darkTextMuted, size: 16),
                  const SizedBox(width: 8),
                  Text(
                    '${_currentCenter!.latitude.toStringAsFixed(5)}, ${_currentCenter!.longitude.toStringAsFixed(5)}',
                    style: const TextStyle(
                      color: Colors.white,
                      fontFamily: 'monospace',
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),

      // Reticle Crosshairs (center when in pin mode)
      if (_isPinMode)
        const Center(
          child: SizedBox(
            width: 120,
            height: 120,
            child: CustomPaint(painter: _ReticlePainter()),
          ),
        ),
      
      // Background picker button (left-aligned, adjusts position in pin mode)
      Positioned(
        bottom: 16,
        left: 16,
        child: _buildBackgroundButton(),
      ),

      // Right floating buttons: Saved Spots + Pin (hidden in pin mode)
      if (!_isPinMode)
        Positioned(
          bottom: 16,
          right: 16,
          child: _buildRightFloatingButtons(),
        ),
      
      // Initial loading overlay (first app launch — hard block)
      if (!_initialCenterReady || _isLoading)
        Positioned.fill(
          child: Container(
            color: AppColors.darkBackground,
            child: const Center(
              child: CircularProgressIndicator(
                color: AppColors.info,
              ),
            ),
          ),
        ),
      
      // Style-change crossfade — fades out over 400ms once map is ready
      if (_initialCenterReady && !_isLoading)
        IgnorePointer(
          ignoring: _mapFullyReady && !_styleTransitionActive,
          child: AnimatedOpacity(
            opacity: (!_mapFullyReady || _styleTransitionActive) ? 1.0 : 0.0,
            duration: const Duration(milliseconds: 400),
            child: Container(color: AppColors.darkBackground),
          ),
        ),
    ];

    return Scaffold(
      backgroundColor: AppColors.darkBackground,
      body: Stack(
        children: [
          Column(
            children: [
              // Map section: keep the same widget shape so MapLibre isn't remounted.
              Expanded(
                flex: mapFlex,
                child: Stack(children: mapChildren),
              ),
              
              // Carousel section (hidden in pin mode)
              if (!_isPinMode)
                SizedBox(
                  height: carouselHeight,
                  child: Container(
                    color: AppColors.darkBackground,
                    child: !_mapFullyReady
                        ? const Center(
                            child: Text(
                              'Loading spots...',
                              style: TextStyle(color: AppColors.darkTextMuted),
                            ),
                          )
                        : _error != null
                            ? Center(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Icon(Icons.error_outline, color: AppColors.darkTextHint, size: 32),
                                    const SizedBox(height: 8),
                                    Text(
                                      'Failed to load spots',
                                      style: TextStyle(color: AppColors.darkTextMuted),
                                    ),
                                    const SizedBox(height: 12),
                                    TextButton(
                                      onPressed: _loadAllSpots,
                                      child: const Text('Retry'),
                                    ),
                                  ],
                                ),
                              )
                            : _visibleSpots.isEmpty
                                ? const Center(
                                    child: Text(
                                      'No spots in view — pan or zoom the map',
                                      style: TextStyle(color: AppColors.darkTextMuted),
                                    ),
                                  )
                                : _buildSpotCarousel(),
                  ),
                ),

              // Pin mode: docked bottom bar replacing nav bar
              if (_isPinMode)
                _buildPinModeActions(),
            ],
          ),

        ],
      ),
    );
  }

  Widget _buildBackgroundButton() {
    return GestureDetector(
      onTap: _showBackgroundPicker,
      child: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: AppColors.darkSurface.withOpacity(0.9),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.darkTextHint),
        ),
        child: const Icon(
          Icons.layers,
          color: AppColors.darkTextSecondary,
          size: 22,
        ),
      ),
    );
  }

  /// Right floating buttons: Fishing targets (top) + My Spots + Pin/Add spot
  Widget _buildRightFloatingButtons() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Offshore fishing targets (SatCatch-style polygons)
        _buildFloatingButton(
          icon: Icons.satellite_alt,
          onTap: _openFishingTargets,
        ),
        const SizedBox(height: 8),
        // Saved spots button
        _buildFloatingButton(
          icon: Icons.bookmark_outline,
          onTap: _showSavedSpotsSheet,
          badgeCount: _userSpots.length,
        ),
        const SizedBox(height: 8),
        // Pin/Add spot button
        _buildFloatingButton(
          icon: Icons.add_location_alt,
          onTap: _enterPinMode,
        ),
      ],
    );
  }

  void _openFishingTargets() {
    final center = _mapController?.cameraPosition?.target ?? _currentCenter ?? _defaultCenter;
    // Copernicus NRT SST/SSTA/CHL lags ~1-2 days; latest available date is yesterday UTC.
    final latest = DateTime.now().toUtc().subtract(const Duration(days: 1));
    final date = '${latest.year.toString().padLeft(4, '0')}-'
        '${latest.month.toString().padLeft(2, '0')}-'
        '${latest.day.toString().padLeft(2, '0')}';
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => PfzZonesScreen(
          lat: center.latitude,
          lon: center.longitude,
          date: date,
        ),
      ),
    );
  }

  Widget _buildFloatingButton({
    required IconData icon,
    required VoidCallback onTap,
    int badgeCount = 0,
  }) {
    return GestureDetector(
      onTap: () {
        HapticFeedback.lightImpact();
        onTap();
      },
      child: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: AppColors.darkSurface.withOpacity(0.9),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.darkTextHint),
        ),
        child: Stack(
          children: [
            Center(
              child: Icon(icon, color: AppColors.darkTextSecondary, size: 22),
            ),
            if (badgeCount > 0)
              Positioned(
                top: 4,
                right: 4,
                child: Container(
                  constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
                  padding: const EdgeInsets.all(2),
                  decoration: BoxDecoration(
                    color: AppColors.info,
                    shape: BoxShape.circle,
                  ),
                  child: Center(
                    child: Text(
                      badgeCount > 9 ? '9+' : '$badgeCount',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 9,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  /// Pin mode bottom bar — docked at bottom edge, replacing the navigation bar
  Widget _buildPinModeActions() {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.darkBackground,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.3),
            blurRadius: 10,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: Row(
            children: [
              Expanded(
                child: GestureDetector(
                  onTap: _exitPinMode,
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    decoration: BoxDecoration(
                      color: AppColors.darkBorder,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: AppColors.darkTextHint),
                    ),
                    child: const Center(
                      child: Text(
                        'Cancel',
                        style: TextStyle(color: AppColors.darkTextSecondary, fontSize: 15),
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: GestureDetector(
                  onTap: _confirmPinLocation,
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    decoration: BoxDecoration(
                      color: AppColors.info,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: const Center(
                      child: Text(
                        'Mark Spot',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSpotCarousel() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
          child: Row(
            children: [
              Text(
                '${_visibleSpots.length} spots in view',
                style: const TextStyle(
                  color: AppColors.darkTextMuted,
                  fontSize: 12,
                ),
              ),
              const Spacer(),
              Text(
                '${_allSpots.length} total',
                style: const TextStyle(
                  color: AppColors.darkTextMuted,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
        
        Expanded(
          child: PageView.builder(
            controller: _carouselController,
            itemCount: _visibleSpots.length,
            onPageChanged: _onSpotSelected,
            itemBuilder: (context, index) {
              final spot = _visibleSpots[index];
              final isSelected = index == _selectedSpotIndex;
              
              return AnimatedScale(
                scale: isSelected ? 1.0 : 0.95,
                duration: const Duration(milliseconds: 200),
                child: GestureDetector(
                  onTap: () => _openSpotDetail(spot),
                  child: _SpotMarkerCard(spot: spot),
                ),
              );
            },
          ),
        ),
        
        // Carousel dots
        Padding(
          padding: const EdgeInsets.only(top: 6, bottom: 6),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(
              _visibleSpots.length.clamp(0, 10),
              (index) => AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                margin: const EdgeInsets.symmetric(horizontal: 3),
                width: index == _selectedSpotIndex ? 16 : 6,
                height: 6,
                decoration: BoxDecoration(
                  color: index == _selectedSpotIndex
                      ? AppColors.info
                      : AppColors.darkTextHint,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// Lightweight spot card for carousel (uses SpotMapMarker, no conditions)
/// Tap to open full detail page where conditions are shown
class _SpotMarkerCard extends StatelessWidget {
  final SpotMapMarker spot;

  const _SpotMarkerCard({required this.spot});

  Color _getScoreColor(int score) => AppColors.getScoreColor(score);

  String _getScoreLabel(int score) => AppColors.getScoreLabel(score);

  @override
  Widget build(BuildContext context) {
    final units = UnitPreferenceService();
    return ListenableBuilder(
      listenable: units,
      builder: (context, _) => _buildCard(context, units.system),
    );
  }

  Widget _buildCard(BuildContext context, UnitSystem system) {
    final score = spot.shakaScore ?? 0;
    final isLoading = spot.isUserSpot && (spot.shakaScore == null || spot.swell == null);
    
    return Opacity(
      opacity: isLoading ? 0.7 : 1.0,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: AppColors.darkTextHint,
          ),
        ),
        child: Row(
          children: [
            // Vertical tier pill
            spot.shakaScore != null
                ? ScoreTierPill(score: score, width: 12, height: 48, vertical: true)
                : isLoading
                    ? const SizedBox(
                        width: 12,
                        height: 48,
                        child: Center(
                          child: SizedBox(
                            width: 12,
                            height: 12,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppColors.info,
                            ),
                          ),
                        ),
                      )
                    : const SizedBox(width: 12, height: 48),
            const SizedBox(width: 12),
            
            // Spot info
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          spot.name,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      // Saved spot indicator
                      if (spot.isUserSpot)
                        const Padding(
                          padding: EdgeInsets.only(left: 6),
                          child: Icon(Icons.bookmark, color: AppColors.info, size: 16),
                        ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  if (isLoading)
                    Text(
                      'Getting intel on this spot...',
                      style: TextStyle(
                        color: AppColors.info,
                        fontSize: 12,
                      ),
                    )
                  else ...[
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: _getScoreColor(score).withOpacity(0.2),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            spot.shakaScore != null ? _getScoreLabel(score) : 'Loading...',
                            style: TextStyle(
                              color: _getScoreColor(score),
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            spot.region,
                            style: const TextStyle(
                              color: AppColors.darkTextMuted,
                              fontSize: 12,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                    // Condition row (visibility + swell + wind) with bordered chips
                    if (spot.visibility != null || spot.swell != null || spot.wind != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Row(
                          children: [
                            if (spot.visibility != null && spot.visibility != 'No satellite data') ...[
                              Flexible(
                                child: Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                                  decoration: BoxDecoration(
                                    border: Border.all(color: AppColors.darkTextHint),
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Icon(Icons.visibility, size: 11, color: AppColors.darkTextMuted),
                                      const SizedBox(width: 4),
                                      Flexible(
                                        child: Text(
                                          spot.visibility!,
                                          style: const TextStyle(color: AppColors.darkTextSecondary, fontSize: 10),
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                              const SizedBox(width: 6),
                            ],
                            if (spot.swell != null) ...[
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                                decoration: BoxDecoration(
                                  border: Border.all(color: AppColors.darkTextHint),
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Icon(Icons.waves, size: 11, color: AppColors.darkTextMuted),
                                    const SizedBox(width: 4),
                                    Text(
                                      spot.swellHeightFt != null
                                          ? UnitConverter.formatSwellHeight(spot.swellHeightFt, system)
                                          : spot.swell!,
                                      style: const TextStyle(color: AppColors.darkTextSecondary, fontSize: 10),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 6),
                            ],
                            if (spot.wind != null)
                              // Same near-real-time source as the detail
                              // screen (upgrades in place from the snapshot),
                              // so tapping through never changes the number.
                              LiveWindValue(
                                spotId: spot.id,
                                builder: (context, live) => Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                                  decoration: BoxDecoration(
                                    border: Border.all(color: AppColors.darkTextHint),
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Icon(Icons.air, size: 11, color: AppColors.darkTextMuted),
                                      const SizedBox(width: 4),
                                      Text(
                                        live != null
                                            ? WindFormat.speedLabel(live.windSpeedKts, system)
                                            : spot.windSpeedKts != null
                                                ? UnitConverter.formatWind(spot.windSpeedKts, null, system)
                                                : spot.wind!,
                                        style: const TextStyle(color: AppColors.darkTextSecondary, fontSize: 10),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                  ],
                ],
              ),
            ),
            
            // Chevron hint
            const Icon(Icons.chevron_right, color: AppColors.darkTextHint, size: 24),
          ],
        ),
      ),
    );
  }
}

/// Card for saved spot in bottom sheet (same as Gibs _SavedSpotCard)
class _SavedSpotCard extends StatelessWidget {
  final UserSpotResponse spot;
  final VoidCallback? onTap;
  final VoidCallback onDelete;
  final bool isLoading;

  const _SavedSpotCard({
    required this.spot,
    required this.onTap,
    required this.onDelete,
    this.isLoading = false,
  });

  @override
  Widget build(BuildContext context) {
    final hasScore = spot.shakaScore != null;
    final scoreColor = hasScore 
        ? AppColors.getScoreColor(spot.shakaScore!) 
        : Colors.grey;
    
    return GestureDetector(
      onTap: onTap,
      child: Opacity(
        opacity: isLoading ? 0.7 : 1.0,
        child: Container(
          margin: const EdgeInsets.only(bottom: 8),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: AppColors.darkBorder,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            children: [
              // Vertical tier pill
              hasScore
                  ? ScoreTierPill(score: spot.shakaScore ?? 0, width: 10, height: 36, vertical: true)
                  : isLoading
                      ? const SizedBox(
                          width: 10,
                          height: 36,
                          child: Center(
                            child: SizedBox(
                              width: 10,
                              height: 10,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: AppColors.info,
                              ),
                            ),
                          ),
                        )
                      : Icon(
                          Icons.location_on,
                          color: scoreColor,
                          size: 20,
                        ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      spot.name,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      isLoading
                          ? 'Getting intel on this spot...'
                          : '${spot.latitude.toStringAsFixed(4)}°, ${spot.longitude.toStringAsFixed(4)}°',
                      style: TextStyle(
                        color: isLoading ? AppColors.info : AppColors.darkTextMuted,
                        fontSize: 12,
                        fontFamily: isLoading ? null : 'monospace',
                      ),
                    ),
                  ],
                ),
              ),
              IconButton(
                onPressed: onDelete,
                icon: const Icon(Icons.delete_outline, color: AppColors.darkTextHint),
                iconSize: 20,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Rifle scope style reticle for pin mode (same as Gibs)
class _ReticlePainter extends CustomPainter {
  const _ReticlePainter();
  
  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2;
    
    final gap = 12.0;
    final lineLength = radius - gap - 4;
    
    final outlinePaint = Paint()
      ..color = Colors.white.withOpacity(0.4)
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    
    final linePaint = Paint()
      ..color = Colors.black
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round;
    
    for (final paint in [outlinePaint, linePaint]) {
      canvas.drawLine(
        Offset(center.dx, center.dy - gap),
        Offset(center.dx, center.dy - gap - lineLength),
        paint,
      );
      canvas.drawLine(
        Offset(center.dx, center.dy + gap),
        Offset(center.dx, center.dy + gap + lineLength),
        paint,
      );
      canvas.drawLine(
        Offset(center.dx - gap, center.dy),
        Offset(center.dx - gap - lineLength, center.dy),
        paint,
      );
      canvas.drawLine(
        Offset(center.dx + gap, center.dy),
        Offset(center.dx + gap + lineLength, center.dy),
        paint,
      );
    }
    
    final dotPaint = Paint()..color = Colors.red;
    canvas.drawCircle(center, 3, dotPaint);
  }
  
  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
