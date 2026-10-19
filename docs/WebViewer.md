# CamMonitor Web Viewer — Android → MacBook / iPhone

## Overview

The Android device stays in **Camera Mode**, retaining the CameraX/WebRTC sender.
A small static web client running on **GitHub Pages** uses browser WebRTC to
display the camera from Safari on iPhone or a browser on MacBook.

The browsers can be on a different Wi-Fi, or cellular network, subject to
NAT traversal. **STUN is insufficient for some networks; an independent TURN
relay with credentials is needed for reliable cross-network connectivity.**

### Architecture

```text
Android CamMonitor (Camera Mode)
  │  CameraX → WebRTC DTLS-SRTP video/audio
  │                     ├───── P2P when NAT allows ────────────┐
  │                     └───── TURN relay (when configured) ───┤
  │  Encrypted/signature-checked signaling                     ▼
  └── MQTT TCP broker.hivemq.com:1883 ◄──► MQTT WSS:8884 ─ Web browser
                                            (MacBook/iPhone)
```

No incoming ports or port-forwarding are needed. Public HiveMQ is only used
as a *development/test* signaling broker; the WebRTC media does **not** traverse
the MQTT broker.

## Deployment (one-time)

1. Merge this feature to `main` when reviewed.
2. GitHub repository → **Settings → Pages → Build and deployment → Source: GitHub Actions**.
3. Run **Actions → Publish CamMonitor Web Viewer → Run workflow** if no deployment
   appears automatically.
4. Open **https://cocoaswifty.github.io/CamMonitor/web-viewer/**.
   If this URL is not available, GitHub Pages has not been activated/deployed yet.

The site is plain HTML/CSS/ES modules and has no CDN runtime dependency.

## First-time browser pairing

1. Android opens CamMonitor → tap the **laptop icon** in the top bar
   (Pair Web Viewer).
2. Optional: set TURN URI, username and credential on the Android pairing screen.
   Examples: `turn:your-turn.example.org:3478` and
   `turns:your-turn.example.org:5349`. A real TURN server is not included.
   Store a *short-lived* credential from your relay provider if possible.
3. Android displays a five-minute QR code, plus Copy / Share link. A link looks
   like `https://cocoaswifty.github.io/CamMonitor/web-viewer/#pair=...`.
4. On iPhone scan the code into Safari. On MacBook use the **privately shared**
   link. The fragment carrying the secret is not transmitted in an HTTP request,
   but anybody possessing the unexpired link can attempt to pair.
5. On the web page tap **Pair this browser**. Keep Android on the pairing
   screen until it sends a `PAIR_CONFIRM`, then Android opens Camera Mode.
6. Tap **Watch live video** in the browser.

**Each browser profile has its own ECDH/ECDSA identity and occupies one
viewer slot**; the camera currently permits up to two paired viewers. To
pair both MacBook and iPhone, pair the first, return to Android's pairing
screen, and pair the second.

Browser keys are held as non-extractable WebCrypto `CryptoKey` handles in
IndexedDB. Safari may purge site storage: repeat pairing if necessary.
Use **Manage Devices** on Android to revoke paired browsers. The browser's
"Forget" control only removes browser-local credentials.

## WebRTC and security

- Java and browser exchange the existing `SignalingEnvelope` structure.
- One-use OTP uses existing project empty-salt KDF behavior and AES-256-GCM.
- Long-term messages use P-256 ECDH with signed SHA-256 ECDSA envelopes,
  replay protection and short timestamp tolerance.
- The website transforms IEEE P1363 signatures to the DER signatures Android
  expects and vice versa.
- Browser connection to MQTT uses `wss://broker.hivemq.com:8884/mqtt`.
- The **existing Android MQTT client still uses unencrypted TCP port 1883**.
  Payloads are app-encrypted, but metadata is exposed: this must be migrated
  to TLS MQTT with hostname validation for production.
- WebRTC protects media in transport using DTLS-SRTP.
- No camera stream or TURN password is hard-coded into the published site.
- A pairing invitation includes the user's TURN connection parameters in
  the **URL fragment** to configure the browser's ICE relay. Keep that
  fragment private; treat the entire link as a secret.
- There is no recording, video upload, or cloud storage in the browser client.
  The Android Smart Recording feature remains a **separate mode**.

## Limits / development validation

- Requires a running Android Camera Mode service and network connectivity.
- Background iPhone Safari may suspend a video connection.
- Chrome/Safari autoplay policy may require a tap; audio is muted by default.
- Public MQTT demo service has no availability guarantee or account isolation.
- No bundled TURN server, relay account, or TURN credentials.
- Changing TURN on Android affects new Android PeerConnections; existing
  web browsers must be re-paired to get the new configuration.
- Only Android-side camera controls on the browser are basic video, sound,
  fullscreen and battery data; advanced PTT/torch/night controls remain in
  the original Android viewer.
- **Not yet verified by building APK or end-to-end testing on an actual
  Android + MacBook + iPhone across separate networks**.

### Test matrix

1. Enable Pages and verify page loads over HTTPS on Safari and Chrome.
2. Pair iPhone, run Android Camera Mode, test on same Wi-Fi.
3. Repeat from iPhone mobile data and MacBook on a different Wi-Fi.
4. Verify ICE candidate `relay` traffic via TURN when P2P fails.
5. Pair second browser; ensure a third cannot bypass two-viewer capacity.
6. Revoke browser in Android Manage Devices and confirm connection is denied.
7. Clear Safari site data, observe need to re-pair.
8. Restart Android Camera Mode; click Watch again.
9. Disable broker/WSS and check that private video isn't publicly exposed.
10. Validate keyboard/video controls and Safari playback restrictions.
