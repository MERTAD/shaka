import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';
import '../../../core/theme/app_colors.dart';
import '../../../core/utils/animations.dart';
import '../../../data/models/spot_models.dart';
import '../../bloc/search_bloc.dart';
import '../../widgets/spot_card.dart';
import 'map_view.dart';
import 'pfz_zones_screen.dart';

class ResultsScreen extends StatefulWidget {
  final double lat;
  final double lon;
  final String date;
  final String locationName;

  const ResultsScreen({
    super.key,
    required this.lat,
    required this.lon,
    required this.date,
    required this.locationName,
  });

  @override
  State<ResultsScreen> createState() => _ResultsScreenState();
}

class _ResultsScreenState extends State<ResultsScreen> {
  bool _isMapView = false;
  bool _hasLoaded = false;

  @override
  void initState() {
    super.initState();
    // Delay to ensure context is ready
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_hasLoaded) {
        _hasLoaded = true;
        _loadSpots();
      }
    });
  }

  void _loadSpots() {
    debugPrint('🔍 Loading spots: lat=${widget.lat}, lon=${widget.lon}, date=${widget.date}');
    context.read<SearchBloc>().add(SearchSpots(
      lat: widget.lat,
      lon: widget.lon,
      date: widget.date,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: Padding(
          padding: const EdgeInsets.only(left: 12),
          child: TextButton(
            onPressed: () {
              HapticFeedback.lightImpact();
              context.pop();
            },
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
              widget.locationName.isNotEmpty ? widget.locationName : 'Results',
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
            tooltip: 'Offshore fishing targets',
            onPressed: () {
              HapticFeedback.lightImpact();
              Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => PfzZonesScreen(
                    lat: widget.lat,
                    lon: widget.lon,
                    date: widget.date,
                  ),
                ),
              );
            },
            icon: const Icon(Icons.satellite_alt),
          ),
          TextButton(
            onPressed: () {
              HapticFeedback.lightImpact();
              setState(() {
                _isMapView = !_isMapView;
              });
            },
            child: Text(
              _isMapView ? 'List' : 'Map',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: AppColors.textSecondary,
              ),
            ),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: BlocBuilder<SearchBloc, SearchState>(
        builder: (context, state) {
          debugPrint('🔄 SearchState: ${state.runtimeType}');
          
          if (state is SearchLoading) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const CircularProgressIndicator(
                    strokeWidth: 2,
                    color: AppColors.oceanBlue,
                  ),
                  const SizedBox(height: 24),
                  Text(
                    'Finding dive spots...',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: AppColors.textMuted,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'This may take a moment',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
            );
          }

          if (state is SearchError) {
            return _buildError(state.message);
          }

          if (state is SearchSuccess) {
            if (state.response.spots.isEmpty) {
              return _buildEmpty();
            }
            return AnimatedSwitcher(
              duration: AppAnimations.pageTransition,
              child: _isMapView
                  ? _buildMapView(state.response.spots)
                  : _buildListView(state.response.spots),
            );
          }

          return const SizedBox.shrink();
        },
      ),
    );
  }

  Widget _buildListView(List<SpotSummary> spots) {
    return ListView.separated(
      key: const ValueKey('list'),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      itemCount: spots.length,
      separatorBuilder: (_, __) => const SizedBox(height: 16),
      itemBuilder: (context, index) {
        final spot = spots[index];
        return SpotCard(
          spot: spot,
          onTap: () {
            HapticFeedback.lightImpact();
            context.push(
              '/spot/${spot.id}',
              extra: {'date': widget.date, 'spot': spot},
            );
          },
        );
      },
    );
  }

  Widget _buildMapView(List<SpotSummary> spots) {
    return MapView(
      key: const ValueKey('map'),
      spots: spots,
      centerLat: widget.lat,
      centerLon: widget.lon,
      onSpotTap: (spot) {
        HapticFeedback.lightImpact();
        context.push(
          '/spot/${spot.id}',
          extra: {'date': widget.date, 'spot': spot},
        );
      },
    );
  }

  Widget _buildEmpty() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              'No spots found',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            Text(
              'Try a different location or expand your search.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: AppColors.textSecondary,
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildError(String message) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              'Unable to load',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            Text(
              message,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: AppColors.textSecondary,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 28),
            TextButton(
              onPressed: () {
                HapticFeedback.lightImpact();
                _loadSpots();
              },
              child: Text(
                'Retry',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: AppColors.oceanBlue,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
