# Temporary remote analyzer connection

The child app includes a server configuration card. Complete signed pairing
with the guardian, install an age profile and enable accessibility first.
Enter the Linux C++ analyzer's `tls://hostname:8443` endpoint, its private
connection token, and optionally a SHA-256 leaf certificate fingerprint for a
self-signed certificate. Server-side setup is documented in k230-control's
`docs/REMOTE_SERVER.md`; this endpoint is a TLS binary service, not Firebase
or a browser page.

The token is not written to preferences or logs. The foreground service keeps
it only for the current connection. On Android 14+ the app requests full-display
sharing, validates capture dimensions before uploading frames, and stops if
dimensions change. Android consent is required again after stopping. A persistent
Arabic notification explains sharing and provides a stop action.

The optional mode sends PNG images, at up to four per second, to the analyzer
over TLS. The server decrypts them to run YOLO/NSFWJS, without saving media.
Encrypted guardian reports continue to originate on the phone. They still
require the existing Firebase relay configuration for remote delivery.

Clock probes must refer to timestamps observed by this process. The bridge
connects to the existing local Companion socket using the app's own UID only
while capture is active. Normal shell/root USB peers retain the existing path.
Both paths retain decision expiry, screen identity, age policy, execution
journal and ACK gates. Opening Mentor's own FLAG_SECURE UI does not yield an
inspectable underlying screen: after permission, open another application.

This initial mode does not stream audio, perform offline model inference,
reconnect automatically, create Firebase accounts or force-stop other apps.
Disconnect stops new analysis; an existing verified shield is not reported as
released because transport closed. A physical phone test and actual deployment
are still needed. Never expose ADB to the Internet for this mode.
