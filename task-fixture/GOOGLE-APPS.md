# Real Google apps emulator

`road-home-google-api29` boots Android 10 with real Google Maps, YouTube,
Play services, and Google Services Framework at 1280 × 720, 240 dpi.
The separate `road-home-api29` AVD retains the offline fixtures.

## Image setup

Google APIs API 29 revision 13 uses a different platform certificate from the
public AOSP development certificate used by Road Home. The local integration
image combines the AOSP API 29 revision 8 OS with Google's original signed APKs
and their package-specific permissions. It includes the Google image's
`LicenseChecker.apk` (`com.android.vending`) in its original system-app location.
This is a compatibility stub, not the full Play Store UI. No Google account was
used. Road Home is platform-signed and installed as a privileged system app,
with DUMP and MEDIA_CONTENT_CONTROL allowlisted. The per-display-focus overlay
from the fixture emulator is included.

The image lives at
`~/.android/avd/road-home-google-api29.avd/gapps-system-image/`.
Local construction scripts, source partitions, APKs, and diagnostic logs are
retained under ignored `build/google-emulator/`. Proprietary APKs and system
images are not distributed in this repository. The construction scripts are
local experiment evidence, not a supported one-command installer.

Source system-image SHA-256 digests:

- AOSP revision 8: `1cec62e86c2cb2bf470bb0c530f2a2514af97eadc919667623ff6cac9034d1d6`
- Google APIs revision 13: `90485becf742d6bc4bb46d4bed148c8b53c4b7c6929d0541fb687b51ab0b8f51`

Resizing system removed its AVB footer. Rebuilding system/vendor hashtrees and
matching the original AOSP vbmeta structure repaired early boot: chain system,
include the vendor descriptor directly in root vbmeta, then update the emulator's
VerifiedBootParams digest and size. The public AOSP development keys match these
public-key SHA-1 fingerprints:

- Root RSA-4096: `2597c218aae470a130f61162feaae70afd97f011`
- System RSA-2048: `cdbb77177f731920bbe0a0f94f84d9038ae0617d`

AVB verification flags remain zero and APK signature enforcement remains enabled.
The privileged-permission allowlist repaired the subsequent system_server startup
failure. Repeated boots reached `sys.boot_completed=1`.

## App updates

The bundled 2020 apps were updated using Google Play downloads obtained through
Aurora Store 4.8.4 anonymous access. Original signatures were verified and the
API-29/x86_64 split packages installed through normal Android package updates.

| App | Version | Version code |
| --- | --- | --- |
| Maps | 26.43.01.1002020710 | 1068844695 |
| YouTube | 21.43.523 | 1561307650 |
| Play services | 26.42.31 (100800-1005867181) | 264231017 |

## Integration findings

Real YouTube exposed an Android 10 system_server crash when its automatically
pinned stack moved directly onto an otherwise empty virtual display:
`IllegalStateException: Failed to find a stack behind ... mode=pinned` in
`ActivityMetricsLogger.logWindowState`. Road Home now restores the exact task
with `startActivityFromRecents` and explicit fullscreen mode. The same operation
returns the remembered task to the main display for Open YouTube, avoiding the
second task created by a package launch intent.

Actual streamed video played beside live Maps tiles after this fix. Pausing in
the panel showed the actual video thumbnail. Artwork now uses center-crop to
fill the 16:9 card instead of leaving black bars around fitted artwork.

Android 10's stock ActivityView powers its virtual display off when its surface
is hidden and destroys embedded tasks when its owner process exits. Maps now
uses a lifecycle adapter that hides its container without powering the display
off. Its replacement virtual display omits DESTROY_CONTENT_ON_REMOVAL, allowing
Android to move Maps to display 0 when the launcher exits or updates. The next
launcher instance adopts that same task. Normal WindowManager display-metric
synchronization stays enabled. The IME transform is retained while hidden:
clearing it while Maps remains resumed exposed an Android 10 null-Matrix crash.

Fullscreen continues to move the existing task to display 0. A trial that expanded
the virtual display instead was removed because Home could target the embedded
display after map interaction and fail to restore the sidebar.

Earlier experiments showed stale-sized rendering and black tiles. A one-time
Maps restart cleared that existing renderer state. Subsequent checks used the
same Maps process through repeated launcher APK replacements and fullscreen/Home
cycles, with working tiles. Disabling HWUI buffer-age caching was inconclusive;
the default was restored and no emulator graphics workaround is required by this
build. These trials do not establish long-duration renderer stability.

YouTube also deactivates its paused session when backgrounded. Road Home retains
that session's live metadata while its task exists, continues observing it when
Home is visible, and drops the selection when its title clears or session is
destroyed. It does not autoplay an inactive selected video.

Use T3 device_open and its bound agent-device command for UI work. Use the
release variant with real package names, signed with `scripts/sign-px6.py
--target aosp29`; the emulator variant targets fixture packages instead.

## Limits

This emulator cannot verify vehicle suspend/resume, MCU mute, CAN data, or the
reported silent playback after ignition. Playback capture uses an emulator
started without audio. APK replacement still destroys the video display and
can stop embedded video playback; map-task preservation is handled separately.
Google sign-in, live route guidance, DRM, and
long-duration stability have not been validated in this AVD.

## Latest validation

Both release and emulator variants pass 25 unit tests each; release assembly and
lint pass. A regression test first reproduced loss of an inactive selected video
and now covers retention, cleared metadata, session destruction, and task removal.

The final continuous 78-second recording shows real YouTube video alongside Maps,
Maps layer controls during playback, pause-to-thumbnail, Open YouTube returning
the paused player fullscreen, Home retaining its inactive thumbnail, Maps dock
fullscreen and return, and the app drawer. Maps task 33 and YouTube task 28
remained unchanged during that recording. System server stayed at PID 6663
through the post-fix trials. Capture is silent and includes the actual transition
blanking; it is not evidence of seamless rendering or working vehicle audio.

The display-lifecycle build passed both variants' 25 unit tests, release build,
and lint. A real Maps route preview from Redwood Valley to Ukiah survived APK
replacement, fullscreen, Home, and a YouTube round trip. Its next-turn buttons
remained usable and tiles rendered at both sizes. Maps task 42, ActivityRecord
71538c7, and PID 14733 survived these trials; system_server remained PID 6663.
The paused YouTube thumbnail filled the entire 320 × 180 card after returning
Home. The map display remained ON while YouTube was foregrounded. This was a
stationary route preview, not a live-guidance or vehicle-audio test.
