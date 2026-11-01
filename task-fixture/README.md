# Offline launcher development

Use the dedicated `road-home-api29` Android 10 AOSP emulator whenever the PX6 is unavailable. It uses 1280 x 720 pixels at 240 dpi. Android 16 emulators can run the decoder probe, but cannot validate Android 10's ActivityView implementation.

The `app:emulator` build shares the production launcher code and uses a separate package, `dev.augie.roadhome.emulator`. Its Maps/video package constants differ: they point to these local fixtures. Release builds continue to target Google Maps and YouTube. Fixtures have no network permission or Google account dependency, and are never installed on the head unit.

## Build and install

```sh
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleEmulator :task-fixture:assembleMapsDebug :task-fixture:assembleVideoDebug
python scripts/sign-px6.py --target aosp29 \
  app/build/outputs/apk/emulator/app-emulator.apk \
  app/build/outputs/apk/emulator/road-home-aosp29.apk
```

The AOSP emulator certificate was compared with its actual `framework-res.apk`. The signer pins that public development certificate and deletes temporary key material. It does not change signature enforcement. The separate focus repair below modifies a private emulator image copy. The `aosp29` APK cannot replace the PX6 build.

Open the emulator using T3's device tools and use its returned device command for UI actions. Install the signed launcher and both fixture APKs with ADB against that emulator's serial. Set Road Home as Home through Android's chooser when testing the hardware Home action. Do not assume an emulator's serial remains the same across restarts.

## Android 10 accessibility repair

Stock Android 10 can leave the main display without a focused window when an ActivityView takes focus. Its accessibility window observer then returns early, causing snapshots to report insufficient foreground content or time out. Enable `config_perDisplayFocusEnabled` at system-server startup with the emulator-only static overlay.

```sh
sh scripts/build-emulator-overlay.sh
# Shut down road-home-api29 through T3 device_close(shutdown=true), then:
python scripts/prepare-emulator-focus.py
# Reopen road-home-api29 through T3 device_open.
```

The preparation script requires Python 3.11+, Linux `debugfs` from e2fsprogs, and AOSP API 29 x86_64 revision 8. It refuses a running AVD or a different system-image digest. It copies the image into this AVD's `focus-system-image` directory, inserts the signed overlay into the logical vendor partition, verifies the inserted APK, and selects cold boot. The SDK image and other AVDs are untouched. No root shell, permission-enforcement changes, tool-runtime patches, or user-data wipe are required. Re-running the script recreates the copy from the original image.

A normal installed dynamic overlay is too late for this startup setting. This SDK image loads vendor from the super partition inside `system.img`; modifying its separate `vendor.img` does not work. The script pins the exact source image before using its partition offsets. The overlay also requires `ro.kernel.qemu=1`.

To undo: stop this AVD and restore `image.sysdir.1 = system-images/android-29/default/x86_64/` in its `config.ini`. Keep cold boot enabled. App data remains intact. Per-display focus is a deliberate emulator difference, so physical-device focus and audio behavior still need separate validation.

## Repeatable scenarios

The map fixture exposes its task ID, Activity instance ID, display ID, and an in-memory route marker. Mark a route active, open it fullscreen with the dock, return Home, open the video fixture, and return Home again. Both IDs and the marker should remain unchanged. Toggle automatic PiP to exercise the navigation app's Home/PiP transition. A recreated Activity or duplicate task is visible directly in the fixture and `RoadFixture` logs.

The video fixture supplies a local animated test pattern, a quiet 440 Hz tone, a real Android media session, and a distinct thumbnail. Select a paused video and return Home: the card should show the thumbnail without starting playback. Start it explicitly, return Home, and inspect live cropping. Pause, return fullscreen, and clear the title to exercise each card state. Logs report audio-focus changes; losing focus pauses the fixture and gaining focus never automatically restarts it.

The sample is generated with:

```sh
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -t 8 -af volume=0.04 -c:v libx264 -preset ultrafast -crf 30 \
  -pix_fmt yuv420p -c:a aac -b:a 48k -movflags +faststart test.mp4
```

These fixtures validate launcher integration with Android's task, surface, and media APIs. They do not prove Google Maps' route restoration, YouTube's internal decoder behavior, or the PX6's MCU/audio routing after vehicle sleep. Keep those as separate physical-device checks.

## Validation, 2026-10-31

The platform-signed launcher runs on Android 10's actual ActivityView implementation at 1280 x 720, density 240.

The original snapshot failed with `Android snapshot helper returned insufficient foreground app content`, followed by `Daemon request timed out`. After the boot-time focus repair, both display 0 and the embedded display retained a current focused window. Repeated snapshots completed in about 0.1 to 0.4 seconds, and snapshot-directed taps and device screenshots worked. Rebuilding the private image with the preparation script and cold-booting again retained this repair; the first snapshot completed in 0.4 seconds. The original device-tool installation was not modified.

The working emulator reproduced a real black Maps panel: fullscreen map -> Return to Road Home delivered `onNewIntent` without `onStart`, leaving the existing map task on display 0. MainActivity now restores Maps on that return intent as well. The same sequence then moved map task 28 from display 2 to 0 and back to 2, preserving Activity instance `323037e792` and the active route marker. The panel rendered correctly afterward.

Selecting a paused video and returning Home showed its thumbnail and title. Android reported `STATE_PAUSED`, position 0, speed 0. No Play command was sent. A map/video/Home app-switch cycle preserved map task 23 and Activity instance `537dcf58c` before the subsequent fixture APK update.

Twenty unit tests pass in each of the release and emulator variants, including Android 10 Robolectric regressions for delayed video metadata, paused video remaining visible while another app plays audio, and independent session subscriptions. Release/emulator builds and lint pass. Live video exposed an additional surface bug: View clipping cropped the video but leaked the surrounding fixture UI over Maps. Cropping the owned ActivityView container with a SurfaceControl transaction removed that overflow in a device screenshot. Pause displayed the thumbnail, and explicit Play restored the live test pattern.

A subsequent tap on Maps at (860,147) failed with `Touch injection was refused`, including the prescribed `--debug` retry. Android's InputDispatcher reported no touchable window there. Its input dump showed the video portal spanning [98,29][952,510], despite visible video bounds [109,111][429,291], intercepting the map portal beneath it. The current build gives the owned video container a portal bounded to the visible video and clears that region while covered. The correction passed build/tests/lint. During the subsequent recorded emulator walkthrough, taps at (860,195) toggled the map fixture state while video continued playing beside it. The map retained task 40, instance 9a962823f9c, and its active route through fullscreen/Home and video app switches. Pausing restored the thumbnail; opening the video app returned existing task 41 to display 0; clearing its title removed the card on Home. No raw ADB input fallback was used. This verifies the previously blocked map interaction at the default layout; video-player hitboxes and other panel sizes remain untested.

Automatic PiP transitions and physical vehicle suspend/audio behavior remain separate checks. The signed PX6 candidate is `app/build/outputs/apk/release/road-home-px6-session-fix.apk`; it has not been installed on the head unit.
