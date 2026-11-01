# Live YouTube window transitions

A playing preview now stays on the same virtual display when opened fullscreen.
`LiveVideoWindow` reparents the launcher's own ActivityView root into a transparent
overlay, then animates its uniform scale, position, and crop over 280 ms. Home
animates the live surface back and atomically restores its preview parent,
geometry, and input portal. There is no screenshot, frozen-frame cover, task
transfer, display resize, or decoder restart in this round trip.

## Android integration

- Native YouTube touches pass through the owned input portal at fullscreen size.
  The portal is disabled during motion; the overlay blocks touches to Maps below.
- Android 10 routes Home to the last touched virtual display, which cannot host
  the primary launcher. While presenting video, the launcher receives Android's
  `CLOSE_SYSTEM_DIALOGS` notification with reason `homekey` and explicitly launches
  its existing Home task on display 0. Its Home intent starts the return animation
  after the synchronous launch call completes.
- Android's fixed-to-user rotation policy is enabled only for the owned video
  display through `cmd window set-fix-to-user-rotation`. This runs off the main
  thread with a two-second timeout using the launcher's existing platform UID.
  It prevents YouTube's portrait request from rotating the virtual display and
  exposing Maps on half the fullscreen presentation. YouTube can still letterbox
  its portrait watch-page UI inside that fixed display.
- SurfaceFlinger metadata is polled while fullscreen to track the decoder's
  current position, including its watch page. The return targets those bounds.
  The View clip uses card coordinates; the video-source crop belongs only to the
  transformed compositor root. Mixing these coordinate spaces hid offset videos.

No Xposed installation or persistent framework-file replacement is needed for
these Android 10 hooks. The implementation still requires the existing platform
signature/system permissions and Android 10 hidden APIs.

## Lifecycle and fallback

An unavailable or paused preview uses the existing direct task-opening path.
Opening never sends Play. Cold embedding still waits for stable, advancing
frames, so the first acquisition of an externally opened task can take time.
Continuous presentation applies once the playing preview is ready.

Repeated opening requests are coalesced. Missing overlay readiness falls back
after 700 ms. Backgrounding cancels the window without a delayed app launch.
Pausing fullscreen and returning Home preserves the paused media state and allows
subsequent playback to prepare the preview again. Thumbnail artwork still depends
on the app supplying it; without artwork the existing play placeholder is shown.

## Validation

Release build, lint, and 52 unit tests pass. Tests cover geometry/proportions,
source ownership, offset watch-page geometry, advancing frames, cancellation,
window-readiness timeout, duplicate requests, and system Home routing.

Real YouTube and Maps on the Android 10 emulator exercised fullscreen controls,
repeated live fullscreen/Home cycles, pause/Home/resume, and watch-page playback.
One measured round trip preserved decoder layer 4451, with frame counters advancing
from 908 to 1064 while the return was still in progress. The task remained on its
virtual display. Earlier recorded cycles similarly retained one decoder layer
through both endpoints. The recording frame rate does not establish hardware
smoothness. Initial external-app acquisition, APK replacement, vehicle sleep/audio,
and arbitrary YouTube UI changes are separate lifecycle paths.

Previous revisions transferred YouTube to display 0 and released the empty host.
They fixed an oversized preview reveal, but measured 0.95 to 1.98 seconds of black
while creating the returning host. The persistent live window removes that transfer
from the warm round trip. Historical recordings under `build/preview-handoff`
show that superseded behavior.

## PX6 deployment, 2026-11-01

The release APK was signed with the matching PX6 platform certificate and
installed with `adb install -r`, preserving app data. The previous installed APK
was saved locally at `build/live-video/px6-backup/road-home-before.apk`. The obsolete
`dev.augie.roadhome.windowdecor` experiment was uninstalled. The device tool confirmed
Road Home opened.

Hardware screenshots then failed twice with `Could not communicate with device
support. Try refreshing devices.`, including after the prescribed device-list
refresh. Hardware interaction testing stopped there. Do not treat deployment as
validation of head-unit animation smoothness. The emulator recordings are locally
at `build/live-video/fullscreen-home.mp4` and
`build/live-video/watch-page-home.mp4`; the latter includes the final View-clip fix.
