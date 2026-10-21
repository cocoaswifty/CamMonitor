# Smart Event Recording + Live WebRTC (shared CameraX pipeline)

CamMonitor now supports **simultaneous on-device person/animal event recording
and remote WebRTC live viewing** from Android, MacBook browsers and iPhone Safari.

## Operation

1. Android CamMonitor → **Smart Event Recording** (camera icon in top bar).
2. Enable smart recording, then tap **Start camera and remote viewer**.
3. Camera Mode uses a **single CameraX session** with:
   - `Preview` → Android WebRTC video track for paired viewers;
   - `ImageAnalysis` → bundled ML Kit image labeling;
   - `VideoCapture` → local MP4 recording of matching events.
4. With no viewers and no recording, physically unbind the camera. After it
   reports CLOSED, wait **10 seconds**, then reopen and inspect the scene.
   A successful negative result closes it again; a target starts MP4 recording.
   While open, classify approximately every 700 ms. Stop after **4 seconds**
   without a target, using recent successful results rather than model failures.
5. Keep the camera open until recording Finalize completes. If a target returns
   while the previous clip is finalizing, start the next clip before sleeping.
6. A paired browser's authenticated Offer wakes the camera immediately, including
   during the 10-second sleep. Pending connections hold camera demand for up to
   30 seconds; connected viewers hold it until they leave. Event recording and
   viewing share the same session and do not interrupt each other.
7. Clips remain private in Movies/CamMonitorEvents. No cloud upload or clip audio.

Start monitoring once while Android is visible and permissions are granted.
Returning to the home screen, pressing Home or locking the phone leaves the
foreground service and encrypted MQTT listener running. Use **Stop monitoring**
to stop it. Change the smart-recording setting only while monitoring is stopped;
the setting is locked during monitoring to avoid interrupting a clip. Camera
and viewer roles on the same Android phone are mutually exclusive.

With smart recording disabled, the sensor stays off until a paired viewer
requests it; there is no periodic AI probe. The smart switch defaults to off.
A probe with no usable image/model result times out after four seconds and
retries after sleeping; this is not reported as a confirmed negative result.

Android can restrict background camera services, especially after force-stop,
reboot, permission revocation or OEM process termination. Remote wake requires
the foreground monitoring service to remain running and reachable. This is not
a mechanism for bypassing those system restrictions. Scheduling is best effort
under Android power management, not an exact-alarm guarantee.

## Device compatibility

- The shared session requests CameraX `Preview + ImageAnalysis + VideoCapture`
  concurrently. **Not all hardware combinations support this**.
- If the three-use-case bind fails, the application retains live WebRTC preview
  but reports a smart recording error. Event clips cannot be guaranteed on
  unsupported hardware; validate on the actual Android phone.
- CameraX Night Extension rebind is disabled while simultaneous event recording
  is active to avoid cutting clips mid-recording.
- Detection uses scene-level classification, not a dedicated object detector.
  Small, partially hidden, or unusual animal classes may be missed. Human/animal
  labels use a finite allowlist and confidence threshold.
- Video clips have no audio. No automatic clip retention policy, motion alerts,
  or remote recording history UI is implemented.

## Testing

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
Also verify on Android hardware:

- Live WebRTC viewer shows video throughout event recording;
- person and animal labels trigger MP4;
- clip ends after 4 seconds without a label;
- no viewers → hardware CLOSED for 10 seconds between probes;
- remote Offer during sleep, probe, recording and Finalize;
- cross-network MacBook/iPhone viewer can connect while recording;
- returning home preserves monitoring; Stop monitoring releases it;
- 30+ minute capture with heat, orientation, torch and poor lighting;
- unsupported camera combinations fall back to live view with a visible error.

**Compilation and real-device simultaneous streaming/recording must be verified
before relying on the feature for security-critical monitoring.**
