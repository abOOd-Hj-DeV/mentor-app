import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

class CloudDeviceCredentials {
  const CloudDeviceCredentials({
    required this.serverUrl,
    required this.deviceId,
    required this.deviceToken,
    required this.name,
    required this.heartbeatIntervalSeconds,
  });

  final String serverUrl;
  final String deviceId;
  final String deviceToken;
  final String name;
  final int heartbeatIntervalSeconds;

  Map<String, Object> toMap() => {
    'serverUrl': serverUrl,
    'deviceId': deviceId,
    'deviceToken': deviceToken,
    'name': name,
    'heartbeatIntervalSeconds': heartbeatIntervalSeconds,
  };

  static CloudDeviceCredentials fromMap(Map<Object?, Object?> values) =>
      CloudDeviceCredentials(
        serverUrl: values['serverUrl'] as String,
        deviceId: values['deviceId'] as String,
        deviceToken: values['deviceToken'] as String,
        name: values['name'] as String,
        heartbeatIntervalSeconds: (values['heartbeatIntervalSeconds'] as num)
            .toInt(),
      );
}

class CloudApiException implements Exception {
  const CloudApiException(this.statusCode, this.message);

  final int statusCode;
  final String message;

  @override
  String toString() => 'HTTP $statusCode: $message';
}

class CloudDeviceClient {
  CloudDeviceClient._();

  static const _credentialsChannel = MethodChannel('dev.k230.mentor/cloud');
  static final HttpClient _httpClient = HttpClient()
    ..connectionTimeout = const Duration(seconds: 15);

  static Uri parseServerUrl(String value) {
    final uri = Uri.tryParse(value.trim());
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (uri.path.isNotEmpty && uri.path != '/')) {
      throw const FormatException(
        'أدخل عنوان HTTPS للخادم فقط، مثل https://mentor.example',
      );
    }
    return uri.replace(path: '', query: null, fragment: null);
  }

  static Future<CloudDeviceCredentials?> loadCredentials() async {
    final values = await _credentialsChannel.invokeMapMethod<Object?, Object?>(
      'loadCredentials',
    );
    return values == null ? null : CloudDeviceCredentials.fromMap(values);
  }

  static Future<void> saveCredentials(CloudDeviceCredentials credentials) =>
      _credentialsChannel.invokeMethod<void>(
        'saveCredentials',
        credentials.toMap(),
      );

  static Future<void> clearCredentials() =>
      _credentialsChannel.invokeMethod<void>('clearCredentials');

  static Future<CloudDeviceCredentials> claim({
    required String serverUrl,
    required String code,
    required String deviceName,
  }) async {
    final server = parseServerUrl(serverUrl);
    final cleanCode = code.trim();
    final cleanName = deviceName.trim();
    if (!RegExp(r'^\d{6,8}$').hasMatch(cleanCode)) {
      throw const FormatException('رمز الربط يجب أن يتكون من 6 إلى 8 أرقام.');
    }
    if (cleanName.isEmpty || cleanName.length > 80) {
      throw const FormatException('اسم الجهاز مطلوب وبحد أقصى 80 حرفاً.');
    }

    final response = await _request(
      server,
      '/pairing/claim',
      method: 'POST',
      body: {'code': cleanCode, 'deviceName': cleanName, 'platform': 'android'},
    );
    final data = response as Map<String, dynamic>;
    return CloudDeviceCredentials(
      serverUrl: server.toString(),
      deviceId: data['deviceId'] as String,
      deviceToken: data['deviceToken'] as String,
      name: data['name'] as String,
      heartbeatIntervalSeconds: (data['heartbeatIntervalSeconds'] as num)
          .toInt(),
    );
  }

  static Future<void> sendHeartbeat(
    CloudDeviceCredentials credentials, {
    required bool screenCaptureActive,
  }) async {
    await _request(
      parseServerUrl(credentials.serverUrl),
      '/devices/${Uri.encodeComponent(credentials.deviceId)}/heartbeat',
      method: 'POST',
      token: credentials.deviceToken,
      body: {
        'screenCaptureActive': screenCaptureActive,
        'appVersion': '1.0.0-diag.1+2',
      },
    );
  }

  static Future<void> sendTestEvent(CloudDeviceCredentials credentials) async {
    await sendEvent(
      credentials,
      category: 'device_test',
      action: 'log',
      confidence: 1,
    );
  }

  static Future<void> sendEvent(
    CloudDeviceCredentials credentials, {
    required String category,
    required String action,
    required double confidence,
    DateTime? occurredAt,
  }) async {
    final cleanCategory = category.trim();
    if (cleanCategory.isEmpty || cleanCategory.length > 80) {
      throw const FormatException(
        'تصنيف الحدث مطلوب وبحد أقصى 80 حرفاً.',
      );
    }
    if (!const {'warn', 'block', 'log'}.contains(action)) {
      throw const FormatException('نوع الحدث غير صالح.');
    }
    if (!confidence.isFinite || confidence < 0 || confidence > 1) {
      throw const FormatException('قيمة الثقة يجب أن تكون بين 0 و1.');
    }
    await _request(
      parseServerUrl(credentials.serverUrl),
      '/devices/${Uri.encodeComponent(credentials.deviceId)}/events',
      method: 'POST',
      token: credentials.deviceToken,
      body: {
        'category': cleanCategory,
        'action': action,
        'confidence': confidence,
        'occurredAt': (occurredAt ?? DateTime.now()).toUtc().toIso8601String(),
      },
    );
  }

  static Future<void> unlink(CloudDeviceCredentials credentials) async {
    await _request(
      parseServerUrl(credentials.serverUrl),
      '/devices/${Uri.encodeComponent(credentials.deviceId)}',
      method: 'DELETE',
      token: credentials.deviceToken,
    );
  }

  static Future<dynamic> _request(
    Uri server,
    String path, {
    required String method,
    String? token,
    Object? body,
  }) async {
    final uri = server.replace(path: '/api$path', query: null, fragment: null);
    final request = await _httpClient.openUrl(method, uri);
    request.headers
      ..set(HttpHeaders.acceptHeader, 'application/json')
      ..set(HttpHeaders.userAgentHeader, 'Mentor-Android/1.0');
    if (token != null) {
      request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
    }
    if (body != null) {
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode(body));
    }

    final response = await request.close();
    final text = await utf8.decoder.bind(response).join();
    if (response.statusCode < 200 || response.statusCode >= 300) {
      var message = response.reasonPhrase;
      if (text.isNotEmpty) {
        try {
          final errorBody = jsonDecode(text);
          if (errorBody is Map<String, dynamic> &&
              errorBody['error'] is String) {
            message = errorBody['error'] as String;
          }
        } on FormatException {
          // Keep the HTTP status text when the server response is not JSON.
        }
      }
      throw CloudApiException(response.statusCode, message);
    }
    return text.isEmpty ? null : jsonDecode(text);
  }
}
