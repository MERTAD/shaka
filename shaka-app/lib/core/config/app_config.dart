/// Build-time configuration for the Shaka app.
///
/// The API host used to be a hardcoded string duplicated across six files, so
/// pointing the app at a different backend meant editing all of them and
/// rebuilding. It is now a single `--dart-define`:
///
///   flutter build apk --release \
///     --dart-define=SHAKA_API_BASE=https://my-api.up.railway.app
///
///   # local dev against the API running on your PC
///   adb reverse tcp:8080 tcp:8080
///   flutter run --dart-define=SHAKA_API_BASE=http://localhost:8080
///
/// IMPORTANT: pass the origin only, with no trailing slash
/// (`https://host`, not `https://host/`) -- the paths below are appended by
/// interpolation. Everything is `const` so it can still be used in `const`
/// widget trees and const lists; a const cannot strip a trailing slash at
/// runtime, hence the requirement.
///
/// Plain http:// targets only work in debug builds: cleartext traffic is
/// enabled in src/debug/AndroidManifest.xml and deliberately left off for
/// release, which is why an https backend is the right choice for release.
class AppConfig {
  const AppConfig._();

  /// Origin of the Shaka API. Defaults to the upstream production server, so a
  /// fresh clone builds an app that works without any local setup.
  static const String apiBase = String.fromEnvironment(
    'SHAKA_API_BASE',
    defaultValue: 'https://shaka-production.up.railway.app',
  );

  /// Versioned REST root, e.g. `https://host/v1`.
  static const String apiV1 = '$apiBase/v1';

  /// Static legal documents, e.g. `https://host/legal`.
  static const String legalBase = '$apiBase/legal';

  /// Legal acceptance write endpoint, e.g. `https://host/v1/legal/acceptances`.
  static const String legalAcceptances = '$apiBase/v1/legal/acceptances';
}
