# Cross-app decoder surface probe

Two small apps test the proposed direct-video mechanism without YouTube, root, platform signing, ActivityView, or PiP. The source app decodes a generated local H.264 test clip. The receiver passes its Surface over Binder; the source calls MediaCodec.setOutputSurface on the existing decoder thread. Attachment does not configure another decoder, seek, or send Play.

This is a mechanism prototype, not a YouTube patch. It cannot access another application's decoder without code running inside that application.

## Build and local checks

From `wend/`:

```sh
ANDROID_HOME=/path/to/sdk ./gradlew \
  :video-probe:assembleSourceDebug :video-probe:assembleReceiverDebug \
  :video-probe:testSourceDebugUnitTest \
  :video-probe:lintSourceDebug :video-probe:lintReceiverDebug
```

The APKs are under `video-probe/build/outputs/apk/{source,receiver}/debug/`. Both use the same debug signing certificate. The exported service requires a signature permission, so unrelated apps cannot control the decoder. The receiver supplies a Binder lease; normal detach or receiver-process death restores the original output. A stale lease cannot detach a newer receiver.

The source intentionally retains its original TextureView surface while the receiver is foreground. Its foreground service owns the test decoder. Finish the test with **Stop test decoder** in the source app. This foreground-service arrangement is only for the controlled sample; it does not establish how YouTube's background-playback lifecycle should be handled.

The generated asset can be reproduced with:

```sh
ffmpeg -f lavfi -i 'testsrc2=size=640x360:rate=30' -t 15 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -crf 28 \
  -movflags +faststart src/main/assets/test-video.mp4
```

## Validation status

Build, lint, and five JVM routing tests pass. Tests cover refusal to attach while paused, restoration after a pause, stale receiver leases, failed switches, and detaching when no receiver is attached.

The corrected APKs installed and launched on the API 36 TV emulator. Live validation passed:

- Explicit Play started the generated test clip in the source app.
- The receiver displayed actual decoded frames after attachment. The source logged `Decoder output redirected without restarting or seeking` at 18:56:48.566.
- Closing the receiver restored visible video to the original source surface. The source logged `Restored original decoder output` at 18:57:06.704, from the same process and decoder worker.
- After pausing in the source, attachment was refused with `Playback is paused. Attachment did not start playback.`
- The test decoder was stopped and the emulator returned Home after validation.

These checks demonstrate cross-app Surface parceling and MediaCodec.setOutputSurface with the controlled sample. They do not validate YouTube, the PX6 hardware codec, protected playback, or receiver-process-death recovery. No YouTube modification was installed and no app data was cleared.

## Requirements for a real YouTube hook

A production hook must identify the active video decoder, intercept its output-surface changes on the decoder's own thread, retain a valid original surface, and restore it before receiver destruction or codec release. It must handle codec recreation, ads, orientation, app lifecycle, and protected playback without bypassing protection. The launcher must offer a surface only for an already-playing YouTube session. The demo's signature permission cannot be copied unchanged to a separately signed YouTube integration; that integration needs an explicit, validated caller and host trust policy.

No claim is made that YouTube's current player allows this routing safely. That requires inspecting the installed build and running a narrowly scoped hook test on the head unit.
