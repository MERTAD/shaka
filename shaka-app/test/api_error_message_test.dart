import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shaka/data/api/shaka_api_client.dart';

/// An error message is how you find out what the backend is actually doing.
///
/// The bug this guards: [ShakaApiClient] read the error body as
/// `data?['error']`, which assumes the body is a JSON object. It is not always.
/// A proxy's HTML 502 page, an empty 404, and a `text/plain` body all arrive as
/// a `String` from Dio — and indexing a String with a String key throws
/// `type 'String' is not a subtype of type 'int' of 'index'`.
///
/// That single line meant every non-JSON error response, on every endpoint, was
/// reported to the user as a Dart type error. It cost real time to diagnose: the
/// first symptom on device was the PFZ zones screen reading
/// `Could not load zones: type 'String' is not a subtype of type 'int' of
/// 'index'` while the actual cause was a perfectly ordinary 404.
void main() {
  late HttpServer server;
  late int mode;

  setUp(() async {
    // A real socket, because the whole point is what Dio hands back for a body
    // that is not JSON. A mocked DioException cannot reproduce the decode.
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    mode = 0;
    server.listen((request) async {
      final response = request.response;
      switch (mode) {
        // An empty body: what a bare 404 from a router gives.
        case 0:
          response.statusCode = 404;
          response.headers.contentType = ContentType.text;
        // An HTML page: what a reverse proxy gives when the app is down.
        case 1:
          response.statusCode = 502;
          response.headers.contentType = ContentType.html;
          response.write(
            '<html><body><h1>502 Bad Gateway</h1>\n'
            '<p>nginx</p></body></html>',
          );
        // The normal case the code was written for.
        case 2:
          response.statusCode = 404;
          response.headers.contentType = ContentType.json;
          response.write(jsonEncode({'error': 'Unknown species'}));
        // Whitespace-only: must not become an empty message.
        case 3:
          response.statusCode = 404;
          response.headers.contentType = ContentType.text;
          response.write('   \n  ');
        default:
          response.statusCode = 500;
      }
      await response.close();
    });
  });

  tearDown(() async => server.close(force: true));

  ShakaApiClient client() => ShakaApiClient(
        dio: Dio(BaseOptions(
          baseUrl: 'http://127.0.0.1:${server.port}',
          // Accept only 2xx, so Dio raises a badResponse for everything the
          // stub server returns - which is what the error path has to survive.
          validateStatus: (status) => status != null && status < 300,
        )),
      );

  /// Driven through [ShakaApiClient.getZones], which surfaces every non-2xx as
  /// an exception - the same path the zones screen takes, and the one that
  /// produced the original device failure.
  ///
  /// [getZoneHistory] treats a 404 as empty history rather than a failure, so
  /// it has its own path and its own tests below.
  Future<String> messageFor() async {
    try {
      await client().getZones(
        lat: 0,
        lon: 0,
        date: '2026-07-14',
        speciesId: 'bluefin_tuna',
      );
      fail('expected an exception');
    } catch (e) {
      return e.toString();
    }
  }

  test('an empty 404 body does not become a Dart type error', () async {
    mode = 0;
    final message = await messageFor();
    expect(message, isNot(contains('subtype')));
    expect(message, contains('404'));
  });

  test('an HTML error page is reported, not crashed on', () async {
    mode = 1;
    final message = await messageFor();
    expect(message, isNot(contains('subtype')));
    expect(message, contains('502'));
    expect(message, contains('Bad Gateway'));
  });

  test('a JSON error field is still surfaced verbatim', () async {
    mode = 2;
    final message = await messageFor();
    expect(message, contains('404'));
    expect(message, contains('Unknown species'));
  });

  test('a whitespace-only body produces a message rather than nothing', () async {
    mode = 3;
    final message = await messageFor();
    expect(message, isNot(contains('subtype')));
    expect(message, contains('404'));
    // Whitespace collapses to empty, so the body must be described rather than
    // left as a bare "Error 404:" with nothing after it.
    expect(message.trim(), isNot(endsWith('Error 404:')));
    expect(message, contains('empty response body'));
  });

  // The history endpoint answers 404 for two very different reasons: nothing
  // has been persisted for this box yet, which is a normal empty state, and the
  // species is not in the roster, which is a bug worth showing. Both are 404,
  // so the status code alone cannot tell them apart and the body has to.
  group('getZoneHistory', () {
    Future<Object?> historyOrError() async {
      try {
        return await client().getZoneHistory(
          lat: 0,
          lon: 0,
          speciesId: 'bluefin_tuna',
        );
      } catch (e) {
        return e;
      }
    }

    test('an unknown species is raised instead of read as empty', () async {
      mode = 2;
      final result = await historyOrError();
      expect(result, isA<Exception>());
      expect(result.toString(), contains('Unknown species'));
    });

    test('a 404 with no unknown-species reason stays an empty history', () async {
      // A bare router 404 - no body to explain itself, so nothing claims the
      // species is wrong and the screen correctly shows "no history yet".
      mode = 0;
      expect(await historyOrError(), isNull);
    });

    test('an empty 404 body is not a type error on the history path', () async {
      mode = 0;
      final result = await historyOrError();
      expect(result.toString(), isNot(contains('subtype')));
    });
  });
}