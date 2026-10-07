import 'package:dio/dio.dart';
import '../models/spot_models.dart';
import '../models/pfz_models.dart';
import '../services/device_id_service.dart';
import '../../core/config/app_config.dart';

/// API client for Shaka backend
class ShakaApiClient {
  final Dio _dio;

  // Build-configurable: see AppConfig for the --dart-define=SHAKA_API_BASE=... flag.
  static const String baseUrl = AppConfig.apiV1;

  ShakaApiClient({Dio? dio})
      : _dio = dio ??
            Dio(BaseOptions(
              baseUrl: baseUrl,
              connectTimeout: const Duration(seconds: 10),
              receiveTimeout: const Duration(seconds: 120),
              headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json',
              },
            ));

  /// Search for spots near a location
  Future<SearchResponse> searchSpots({
    required double lat,
    required double lon,
    required String date,
    int radiusKm = 160,  // ~100 miles
  }) async {
    final stopwatch = Stopwatch()..start();
    print('🌊 API: Searching spots at ($lat, $lon) radius=$radiusKm date=$date');
    try {
      final response = await _dio.get(
        '/spots/search',
        queryParameters: {
          'lat': lat,
          'lon': lon,
          'radius': radiusKm,
          'date': date,
        },
      );
      stopwatch.stop();
      print('✅ API: Search completed in ${stopwatch.elapsedMilliseconds}ms');
      return SearchResponse.fromJson(response.data);
    } on DioException catch (e) {
      stopwatch.stop();
      print('❌ API: Search failed after ${stopwatch.elapsedMilliseconds}ms - ${e.type}: ${e.message}');
      throw _handleError(e);
    }
  }

  /// Get detailed information for a spot
  Future<SpotDetail> getSpotDetail({
    required String spotId,
    required String date,
  }) async {
    try {
      final response = await _dio.get(
        '/spots/$spotId',
        queryParameters: {'date': date},
      );
      return SpotDetail.fromJson(response.data);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Get forecast for a spot
  Future<List<DayForecast>> getForecast({
    required String spotId,
    int days = 7,
  }) async {
    try {
      final response = await _dio.get(
        '/forecast/$spotId',
        queryParameters: {'days': days},
      );
      return (response.data as List)
          .map((e) => DayForecast.fromJson(e))
          .toList();
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Get hourly swell + wind curves for a spot, grouped by spot-local day.
  /// [cacheId] is the spot id (curated) or `user-{uuid}` for user spots.
  Future<SpotHourlyResponse?> getSpotHourly(String cacheId) async {
    try {
      final response = await _dio.get('/spots/$cacheId/hourly');
      return SpotHourlyResponse.fromJson(response.data);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      throw _handleError(e);
    }
  }

  /// Get near-real-time wind for a spot. Called by the detail screen AFTER the
  /// page paints so the detail load stays instant; returns null if unavailable.
  /// [cacheId] is the spot id (curated) or `user-{uuid}` for user spots.
  Future<LiveWind?> getLiveWind(String cacheId) async {
    try {
      final response = await _dio.get('/spots/$cacheId/wind/live');
      return LiveWind.fromJson(response.data);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      throw _handleError(e);
    }
  }

  /// Get multi-day tide chart curves for a spot, one per spot-local day.
  Future<SpotTideRangeResponse?> getTideRange(String cacheId,
      {int days = 7}) async {
    try {
      final response = await _dio.get(
        '/spots/$cacheId/tide',
        queryParameters: {'days': days},
      );
      return SpotTideRangeResponse.fromJson(response.data);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      throw _handleError(e);
    }
  }

  /// Species-specific Potential Fishing Zone verdicts for a spot and date.
  ///
  /// Returns null when the spot is unknown (404). A cold data cache is NOT an
  /// error — the backend answers with low confidence and named missing factors,
  /// which is the honest state and must reach the user rather than being
  /// swallowed as a failure.
  ///
  /// [mode] and [sizeClass] are sent only when supplied. Bluefin feeding and
  /// spawning are near-inverted models, so omitting them gets the documented
  /// default (feeding, large fish) rather than a guess: the backend echoes back
  /// which mode and size class it actually applied.
  Future<PfzResponse?> getPfz({
    required String spotId,
    required String date,
    String? mode,
    String? sizeClass,
  }) async {
    try {
      final response = await _dio.get(
        '/spots/$spotId/pfz',
        queryParameters: {
          'date': date,
          if (mode != null) 'mode': mode,
          if (sizeClass != null) 'sizeClass': sizeClass,
        },
      );
      return PfzResponse.fromJson(response.data as Map<String, dynamic>);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      throw _handleError(e);
    }
  }

  /// SatCatch-style offshore fishing targets: ranked zones around a lat/lon for
  /// one species and date, each a data-shaped polygon with a named centre.
  ///
  /// Returns the response even when the Copernicus corridor did not resolve —
  /// the backend answers with an empty `zones` list and an honest
  /// `coverageNotes` message rather than a hard error.
  ///
  /// [mode] and [sizeClass] behave as in [getPfz]: a zone scored for one mode is
  /// a different number from the same polygon scored for another, so the applied
  /// context is echoed on every zone.
  Future<PfzZonesResponse?> getZones({
    required double lat,
    required double lon,
    required String date,
    required String speciesId,
    String? mode,
    String? sizeClass,
  }) async {
    try {
      final response = await _dio.get(
        '/pfz/zones',
        queryParameters: {
          'lat': lat,
          'lon': lon,
          'date': date,
          'species': speciesId,
          if (mode != null) 'mode': mode,
          if (sizeClass != null) 'sizeClass': sizeClass,
        },
        // A cold first scan of a region downloads the Copernicus grids from
        // scratch and can take several minutes; the backend's analysis deadline
        // is 420s. The default 120s receive timeout would cut it before it
        // ever answered. Subsequent scans hit the backend's grid cache.
        options: Options(receiveTimeout: const Duration(seconds: 480)),
      );
      return PfzZonesResponse.fromJson(response.data as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Persisted day-over-day history for one zones request.
  ///
  /// Reads what the model said on each past day, so [mode] and [sizeClass] must
  /// match what the `getZones` call used: the model context is part of the
  /// storage key, and a feeding series will not appear in a spawning request.
  ///
  /// An empty history is a normal state, not a failure — a location nobody has
  /// asked about yet has no rows, and the backend says so in `coverageNotes`
  /// rather than 404ing. The only error case worth surfacing is an unknown
  /// species, which would mean the picker and the roster disagree.
  Future<PfzZoneHistory?> getZoneHistory({
    required double lat,
    required double lon,
    required String speciesId,
    int days = 7,
    String? mode,
    String? sizeClass,
  }) async {
    try {
      final response = await _dio.get(
        '/pfz/zones/history',
        queryParameters: {
          'lat': lat,
          'lon': lon,
          'species': speciesId,
          'days': days,
          if (mode != null) 'mode': mode,
          if (sizeClass != null) 'sizeClass': sizeClass,
        },
      );
      return PfzZoneHistory.fromJson(response.data as Map<String, dynamic>);
    } on DioException catch (e) {
      // An unknown species is a 404 like any other, so the status alone cannot
      // decide: swallowing it would show an empty history for a species the
      // roster does not have, which reads as "no data yet" rather than "the
      // picker is wrong". The body is what separates them.
      if (e.response?.statusCode == 404) {
        final body = e.response?.data;
        final error = body is Map ? body['error']?.toString() : null;
        if (error != null && error.contains('Unknown species')) {
          throw Exception('Error 404: $error');
        }
        return null;
      }
      throw _handleError(e);
    }
  }

  /// Get community reports for a region
  Future<List<CommunityReport>> getCommunityReports(String region) async {
    try {
      final response = await _dio.get('/reports/$region');
      return (response.data as List)
          .map((e) => CommunityReport.fromJson(e))
          .toList();
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Batch fetch spots by IDs (for favorites/home screen)
  Future<BatchSpotsResponse> getSpotsBatch({
    required List<String> spotIds,
    required String date,
  }) async {
    if (spotIds.isEmpty) {
      return BatchSpotsResponse(
        spots: [],
        date: date,
        fetchedAt: DateTime.now().toIso8601String(),
      );
    }
    
    try {
      final response = await _dio.get(
        '/spots/batch',
        queryParameters: {
          'ids': spotIds.join(','),
          'date': date,
        },
      );
      return BatchSpotsResponse.fromJson(response.data);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Search spots by name (for type-ahead search)
  Future<List<SpotSearchResult>> searchSpotsByName({
    required String query,
    int limit = 20,
  }) async {
    if (query.trim().isEmpty) {
      return [];
    }
    
    try {
      final response = await _dio.get(
        '/spots/search/name',
        queryParameters: {
          'q': query,
          'limit': limit,
        },
      );
      return (response.data as List)
          .map((e) => SpotSearchResult.fromJson(e))
          .toList();
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Get all regions (for search autocomplete)
  Future<List<RegionInfo>> getRegions() async {
    try {
      final response = await _dio.get('/regions');
      return (response.data as List)
          .map((e) => RegionInfo.fromJson(e))
          .toList();
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Get ALL spots for map display (lightweight, no conditions)
  /// Used by Explore map to load all ~800 spots once on startup.
  Future<AllSpotsResponse> getAllSpots() async {
    final stopwatch = Stopwatch()..start();
    print('🗺️ API: Fetching all spots for map display');
    try {
      final response = await _dio.get('/spots/all');
      stopwatch.stop();
      final result = AllSpotsResponse.fromJson(response.data);
      print('✅ API: Got ${result.count} spots in ${stopwatch.elapsedMilliseconds}ms');
      return result;
    } on DioException catch (e) {
      stopwatch.stop();
      print('❌ API: getAllSpots failed after ${stopwatch.elapsedMilliseconds}ms - ${e.type}: ${e.message}');
      throw _handleError(e);
    }
  }

  /// Get detailed health status of external services
  /// Used for auto-degradation when services are down
  Future<ServiceHealth> getServiceHealth() async {
    try {
      final response = await _dio.get('/health/detailed');
      return ServiceHealth.fromJson(response.data);
    } catch (e) {
      // If health check fails, assume healthy - don't degrade UI
      // just because we can't reach the health endpoint
      return ServiceHealth.healthy();
    }
  }

  // ===========================================
  // USER SPOTS API
  // ===========================================

  /// Create a new user spot
  Future<UserSpotResponse> createUserSpot({
    required String name,
    required double latitude,
    required double longitude,
  }) async {
    final deviceId = await DeviceIdService.getDeviceId();
    print('📍 API: Creating spot "$name" with deviceId=$deviceId');
    try {
      final response = await _dio.post(
        '/user-spots',
        data: {
          'name': name,
          'latitude': latitude,
          'longitude': longitude,
        },
        options: Options(headers: {'X-Device-ID': deviceId}),
      );
      print('📍 API: Spot created successfully - ${response.data}');
      return UserSpotResponse.fromJson(response.data);
    } on DioException catch (e) {
      print('📍 API: Create spot FAILED - ${e.response?.statusCode}: ${e.response?.data}');
      throw _handleError(e);
    }
  }

  /// Get all user spots for this device
  Future<UserSpotsListResponse> getUserSpots() async {
    final deviceId = await DeviceIdService.getDeviceId();
    print('📍 API: Getting spots for deviceId=$deviceId');
    try {
      final response = await _dio.get(
        '/user-spots',
        options: Options(headers: {'X-Device-ID': deviceId}),
      );
      print('📍 API: Got spots response - ${response.data}');
      return UserSpotsListResponse.fromJson(response.data);
    } on DioException catch (e) {
      print('📍 API: Get spots FAILED - ${e.response?.statusCode}: ${e.response?.data}');
      throw _handleError(e);
    }
  }

  /// Get detailed info for a user spot (includes forecast data)
  Future<UserSpotDetailResponse> getUserSpotDetail({
    required String spotId,
    required String date,
  }) async {
    final deviceId = await DeviceIdService.getDeviceId();
    try {
      final response = await _dio.get(
        '/user-spots/$spotId',
        queryParameters: {'date': date},
        options: Options(headers: {'X-Device-ID': deviceId}),
      );
      return UserSpotDetailResponse.fromJson(response.data);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// Delete a user spot
  Future<void> deleteUserSpot(String spotId) async {
    final deviceId = await DeviceIdService.getDeviceId();
    try {
      await _dio.delete(
        '/user-spots/$spotId',
        options: Options(headers: {'X-Device-ID': deviceId}),
      );
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  Exception _handleError(DioException e) {
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
        return Exception('Connection timed out.');
      case DioExceptionType.connectionError:
        return Exception('Unable to connect.');
      case DioExceptionType.badResponse:
        final statusCode = e.response?.statusCode;
        return Exception('Error $statusCode: ${_errorMessage(e.response?.data)}');
      default:
        return Exception('Request failed.');
    }
  }

  /// Pull a human-usable message out of an error body.
  ///
  /// The body is only JSON when the API actually answered. A proxy's HTML 502
  /// page, an empty 404, or a bare `text/plain` body all arrive as a String,
  /// and indexing a String with a String key throws
  /// `type 'String' is not a subtype of type 'int' of 'index'`. That replaced
  /// the real diagnosis with a Dart type error on every such response, which is
  /// the least useful thing an error path can do.
  ///
  /// Truncated and whitespace-collapsed so an HTML page does not arrive as a
  /// 40 KB string inside an exception message.
  String _errorMessage(dynamic body) {
    if (body == null) return 'Unknown error';
    if (body is Map) {
      final error = body['error'];
      if (error != null) return error.toString();
      final message = body['message'];
      if (message != null) return message.toString();
      return 'Unknown error';
    }
    if (body is String) {
      final trimmed = body.trim().replaceAll(RegExp(r'\s+'), ' ');
      if (trimmed.isEmpty) return 'empty response body';
      return trimmed.length <= 200
          ? trimmed
          : '${trimmed.substring(0, 200)}…';
    }
    return body.toString();
  }
}
