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
4. When not recording, sample a frame about every **10 seconds** for a person
   or common animal label. When a target is found, start recording and classify
   more frequently. Stop when no matching label has been seen for **4 seconds**.
5. MP4 clips are stored privately in `Movies/CamMonitorEvents` under the app's
   external-files area. They can be viewed or shared from the Smart Recording
   screen. No cloud upload or continuous audio recording.

The camera stays **open** while smart monitoring is enabled, even with **zero
remote viewers**, so Android can detect events and accept incoming WebRTC offers
immediately. This supersedes the earlier 4-second camera probe / 10-second
physical camera-off duty cycle. In simultaneous mode the **AI sampling interval**
is ten seconds, not the camera opening interval. Continuous capture uses more
battery and can increase device temperature.

When smart monitoring is **disabled**, existing 60-second viewer inactivity
standby and night-extension camera behavior remain unchanged.

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
- no viewers → classification continues;
- cross-network MacBook/iPhone viewer can connect while recording;
- Android stops the camera gracefully on exiting Camera Mode;
- 30+ minute capture with heat, orientation, torch and poor lighting;
- unsupported camera combinations fall back to live view with a visible error.

**Compilation and real-device simultaneous streaming/recording must be verified
before relying on the feature for security-critical monitoring.**
