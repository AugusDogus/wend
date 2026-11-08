# Road Home

A small native launcher for the PX6 running Hal9k Android 10. The real Google Maps app occupies the main panel; active Android media sessions supply the music controls. No map SDK, WebView, network backend, analytics, or copied Mini AA code.

## Current development status

The corrected build uses the standard `release` variant. The `windowed` experiment
forced Android 10's status bar, added a header inset, and hid the dock clock.
It is unsuitable for the intended fullscreen layout. The embedded Maps host now
shares the live surface expansion with video and uses the activity-local Home
return animation. The dock has a distinct opaque background and its original
clock, padding, and divider. See [the correction and validation](docs/maps-window-transitions.md#fullscreen-layout-correction).


This repository captures the current development build, including the Android 10 emulator fixtures and decoder probe. It is not a general-purpose launcher release. Installation requires the target ROM's matching platform signature.

The current source merges two feature lanes: the app catalog, drawer, dock and settings sheet, and the floating video and Maps windows. Each lane was validated on its own on the Android 10 emulators, and the merged build was then exercised on both the fixture emulator and the emulator with real Google Maps and YouTube. None of this has run on the PX6. Each feature doc says what was checked where: [launcher UI](docs/launcher-ui.md#validation-scope), [floating windows](docs/floating-windows.md), [live YouTube window transitions](docs/youtube-window-transitions.md) and [Maps window transitions](docs/maps-window-transitions.md). Earlier emulator checks verified Maps task restoration, paused-video thumbnails, and video surface clipping, and a recording showed map controls usable beside playing video at the default layout. Video-player hitboxes remain untested. Vehicle suspend/audio behavior and the current build on the PX6 remain unverified. See the [offline development guide](task-fixture/README.md) for reproducible setup and the exact validation limits.

## First version

The design gives navigation priority: a narrow dock, a large map, and a continuous media sidebar. No second speedometer or weather tile competes with the map. Native Android sans-serif keeps text familiar and readable at arm's length. The user requested comparison against [Apple CarPlay](https://www.apple.com/ios/carplay/), with their explicit preference for an unrounded sidebar taking precedence over card layouts in Apple's examples.

Controls have at least 48dp touch targets on the target 1280 x 720, 240dpi screen. The dock is 60dp wide, with 6dp horizontal insets around 48dp touch targets, a darker continuous surface, and a one-pixel separator. Maps is flush to the screen's outer edge, with only the sidebar-facing corners rounded. The map can sit on either side and use 60% or 70% of the content width. The default is 70% on the right for the left-hand-drive layout.

The app drawer is a full-width glass bottom sheet with five columns, centered search, and a close control at the start. It opens with the launcher's cached app list and can be dragged down to dismiss. Android's full-screen extracted-text keyboard mode is disabled. App artwork fills its own squircle mask, without an extra colored tile. Adaptive icon foreground/background layers are drawn directly inside that mask.

The drawer and settings sheets share one backdrop: Android 10's privileged screen-capture API and a downsampled RenderScript blur. Capture and blur run off the main thread; captures stay in memory and are never written or transmitted. The API overload excludes secure layers. If capture or blur fails, the event is logged and a translucent fallback is shown. The launcher itself has no Internet permission.

The app drawer, configurable dock and settings sheet are described in [launcher UI behavior and validation](docs/launcher-ui.md).

This intentionally targets Android 10's hidden `ActivityView` API. `platform-stubs` supplies compile-only declarations matching AOSP Android 10; it is not packaged. A matching platform signature is required for the system UID and media/control permissions. Installing this APK grants broad system privileges. It never sets itself as the default home app.

## Embedded video

Opening an already playing YouTube preview expands its live surface into the full app over 280 ms. Home shrinks that same running surface back into the card. The task, virtual display, and decoder stay alive throughout, with native YouTube input at fullscreen size. Android Home routing and virtual-display orientation are handled through platform privileges. No screenshot is stretched, no static frame is held, and no Play command is sent. Paused thumbnails and unavailable previews open directly. See [transition behavior and validation](docs/youtube-window-transitions.md).

Playing video floats over fullscreen Maps, live Maps floats over fullscreen video, a tap swaps them, and Home no longer flashes Maps' system PiP: see [floating windows](docs/floating-windows.md).

The current source replaces the earlier PiP approach. It was installed during the Maps task-lifecycle investigation, but live video cropping is not yet hardware-validated. When YouTube is already playing, it moves the existing task into an ActivityView kept at the main display's full pixel size. It reads SurfaceFlinger's Android 10 layer proto to locate the actual YouTube video SurfaceView, crops to that region, and scales only the presentation into the media panel. There is no PiP request or repeated task resize. The head unit disconnected before a live-video trial could be completed.

Geometry queries run off the UI thread and read layer metadata, not pixels or stream URLs. The parser requires YouTube's layer to descend from a Road Home ActivityView and rejects hidden, secure, protected, rotated, and out-of-display content. The panel remains covered until two queries agree on video bounds; missing geometry produces an explicit failure message after three seconds. `VideoSurfaceTest` covers the geometry and ownership checks. The proto reader and visible surface crop passed an Android 10 emulator fixture trial. SurfaceControl now crops the owned ActivityView container because View clipping alone leaked surrounding app UI. The card no longer forwards touches to YouTube: the whole card is one tap target that opens the video, and map taps beside it reach Maps (see [the video card](docs/floating-windows.md#the-video-card)). All of these paths still need hardware validation.

Embedding never sends Play. A YouTube media session with a nonblank title keeps a 16:9 video card visible even while paused or stopped. Whenever live video is not shown in the card, it shows a recent still of the live video, then session artwork, then a play-control placeholder, so it never reads as an empty black box. Only STATE_PLAYING can start live embedding. Clearing the title or destroying the YouTube session hides the card. An observed session that becomes inactive retains its thumbnail while the YouTube task exists; its metadata is read live, not copied into a permanent cache. An idle Spotify session does not displace a selected paused YouTube video; active playback in another app controls the audio buttons while the selected video card stays visible. The action button uses the selected app label and opens the app on the main display. Reinstalling the launcher destroys its virtual displays and can stop embedded playback.

A separate [cross-app decoder probe](video-probe/README.md) exercises the proposed MediaCodec output-surface handoff with a generated local video. It is not a YouTube patch and is not part of the head-unit APK.

## Build

Requires JDK 17 or newer and Android SDK platform 35.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleRelease :app:lintRelease
```

The unsigned release APK cannot be installed as-is. Sign it with the device's matching platform key using `scripts/sign-px6.py app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/road-home-px6.apk`. That helper is specifically pinned to this PX6's verified public Rockchip development certificate. Do not disable Android signature verification. The release build is not debuggable.

## Experimental live Maps window

The `windowed` build uses a real bounded Maps task on the main display and
animates its live surface into fullscreen. It does not stretch a preview. This
is an archived experiment that was installed on the PX6 on 2026-11-01.
Use `release` for the corrected fullscreen layout. Maps fullscreen/Home was
exercised; hardware animation performance and YouTube expansion remain unverified. The
standard release retains ActivityView. See [implementation and validation
notes](docs/maps-window-transitions.md) for the API findings and remaining limits.

Requirements: Android 10, its matching platform signature/system UID, and
freeform windows enabled in Developer options followed by an Android restart.
The build helper also needs Android SDK build-tools 36.0.0. Build the decoration
overlay against the **exact target device's** framework, preserving its private
resource IDs. The launcher refuses to enable an overlay with a different
framework hash.

For the dedicated AOSP Android 10 emulator:

```sh
mkdir -p build
adb -s emulator-5558 pull /system/framework/framework-res.apk build/framework-res.apk
python scripts/build-window-decoration.py build/framework-res.apk --target aosp29
./gradlew :app:assembleWindowed :app:testWindowedUnitTest :app:lintWindowed
python scripts/sign-px6.py --target aosp29 \
  app/build/outputs/apk/windowed/app-windowed-unsigned.apk \
  app/build/outputs/apk/windowed/road-home-windowed.apk
adb -s emulator-5558 install -r build/window-overlay/window-decor.apk
adb -s emulator-5558 install -r app/build/outputs/apk/windowed/road-home-windowed.apk
```

This replaces Road Home's APK while retaining its data. The overlay removes
**all freeform window titlebars** while enabled. The experimental layout keeps
Android's status bar visible. Fullscreen expansion animates position and crop
of Maps' live surface after its buffers are ready. Home moves and crops the
fullscreen surface back into the panel over 240 ms, then commits its smaller
layout. A stationary, unscaled cover bridges that final layout change without
crossfading. A cold expansion can exceed the readiness deadline and complete
without motion; a missing Home callback also falls back to a direct handoff. Active navigation and vehicle suspend/audio remain unverified
in this variant.

To roll back, build and sign the regular release with the same target key,
install it over the windowed APK, then uninstall `dev.augie.roadhome.windowdecor`
to restore freeform titlebars. Installing the regular launcher alone does not
disable the decoration overlay. Rebuild the overlay after a ROM change; never
reuse the emulator's overlay on the head unit or disable signature checks.

## Trial checks

- Verify APK signing and ensure compile-only framework stubs are absent from DEX.
- Confirm Maps runs on the launcher's virtual display and remains interactive.
- Verify active media metadata and play/pause controls.
- Switch map side/size without crashing or clearing navigation.
- Road Home is now the default launcher. Mini AA and AutoZen were uninstalled at the user's request.
- Reboot and suspend/resume need a separate hardware trial.

## Earlier installed builds: hardware validation, 2026-10-31

The initial build successfully embedded real Google Maps, including live navigation, and displayed Spotify/YouTube session metadata. Moving Maps to the right worked. App-grid filtering for Spotify worked. The first blurred drawer was captured and visually checked, then revised after user feedback.

The latest revision has passed release build and lint, is signed with the verified PX6 platform certificate, and is installed. It removes adjacent rounded panels, reduces excess padding, and tones down the drawer blur/tint. A hardware screenshot showed that parent outline clipping did not round ActivityView's Maps surface and that media without artwork left excessive empty space. The next revision adds small corner masks over the inner Maps corners, hides absent artwork, and groups media metadata and controls vertically.

Installation of that revision succeeded and the device command confirmed Road Home opened. Screenshot capture then failed twice with "Could not communicate with device support. Try refreshing devices.", including after refreshing the device list. Closing and reopening this thread's Device panel subsequently restored capture. Hardware screenshots verified the inner Maps corner masks, flush outer map edge, and grouped media layout without the empty artwork gap. The full-width, five-column drawer was then installed and visually checked; searching for Spotify filtered correctly while the keyboard remained below the drawer instead of opening fullscreen extracted text. Maps touch behavior around the masks, playback buttons, and repeated app switching still need hardware checks. No reboot or long-duration stability claim is made.

Capture investigation: T3's local screenshot endpoint returned HTTP 400 after 8006 ms and 8007 ms. Its installed capture implementation gives the ADB screenshot subprocess an 8000 ms deadline, separate from the scrcpy video stream. A previous capture succeeded in 1493 ms. The head unit remained reachable (106 ms direct Tailscale ping), and a 1 MiB non-screen ADB transfer completed in 2.3 seconds. Road Home's process remained alive and logged successful Maps embedding. These findings localize the failure to the screenshot operation; they do not establish whether Android capture, PNG encoding, or intermittent transport delay caused the timeout. The scrcpy stream remained healthy at about 23 fps during investigation. Resetting only this thread's Device panel restored successive screenshots without rebooting the head unit; the precise internal cause remains unproven. No capture-tool code was changed.

Spotify's new crash was traced separately to WebView updating to 156.0.8078.49 and bypassing the old thermal-service patch. The updated root patch was applied; see `../thermal-webview-patch/docs/WEBVIEW-49-REPAIR.md` for evidence and pending playback validation.

### Dock and video validation

Release build and lint pass. The final lifecycle revision is signed and installed. Device screenshots verified the inset dock and divider, removal of the duplicate source heading, the Open YouTube label, and actual video-only YouTube playback beside Maps. Returning Home with a paused YouTube session left playback paused and did not create a video panel. Open YouTube moved the activity back to a fullscreen stack on display 0, confirmed in Android task state and a screenshot. Returning Home while that video was playing restored video-only playback in the media panel; media state remained `STATE_PLAYING`. Pausing in the embedded panel preserved the visible video. Reboot, vehicle suspend/resume, and long-duration playback remain untested.

### Transition refinement

The two fixed 500 ms waits and second PiP request were removed. Android 10's `TaskStackListener.onPinnedStackAnimationEnded` drives the move; a 2500 ms deadline reports failure instead of moving the task on a timer. The player is fitted without animation while covered, then revealed after a launcher frame commit. Pending transitions are canceled when playback stops or the host stops. An existing main-display PiP animation is allowed to finish before reparenting.

Before/after recordings showed that the redundant delayed fit is removed, but the full transition is not yet seamless: Android's main-display PiP shrink remains visible, and YouTube can briefly retain its full-page layout while processing the mode change. A launcher frame commit is not a fence for YouTube's decoder or app buffers. Do not treat this as a complete animation fix.

### Direct video surface investigation

Read-only SurfaceFlinger inspection found separate YouTube SurfaceView layers on this PX6. AOSP Android 10's Java SurfaceControl interface provides capture operations, but no cross-app live video-surface acquisition API. Layer names are not usable SurfaceControl handles. A hook inside YouTube might redirect its decoder output to a launcher-owned Surface, but that remains unproven and would also need to preserve playback across focus/lifecycle changes. No YouTube APK or decoder hook has been installed for this investigation.

References: [Android 10 SurfaceControl](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/view/SurfaceControl.java), [TaskStackListener](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/app/TaskStackListener.java), [MediaCodec.setOutputSurface](https://developer.android.com/reference/android/media/MediaCodec#setOutputSurface(android.view.Surface)).

The transition refinement passed release build/lint, platform-signature verification, and a check that no compile-only Android framework classes were packaged. The final APK installed successfully, logged a fitted-player reveal, and displayed video beside Maps. Returning Home after pausing retained `STATE_PAUSED`. Screenshot capture remained intermittent.

## Current local work and device tooling diagnosis

Earlier in the session, the car shut down before the no-PiP crop candidate could be installed. The subsequent Maps task-lifecycle build includes that candidate and is now installed. See the validation limits below.

T3's local emulator discovery failure was traced to its installed expo-device-hub 0.19.0-canary-20261023-1357114: `runAdbGetprop` calls `execFileAsync` without a timeout, and `inspectConnectedDevices` awaits each device. Two `adb shell getprop` processes against the powered-off head unit were stuck for several minutes. Disconnecting only that stale ADB transport let both probes exit; `device_list` then returned ready and the local TV emulator opened successfully. This path is server-to-local-hub, independent of the phone UI. No T3 runtime files were modified. A permanent upstream fix still needs bounded per-device probes and discovery that preserves healthy devices after one probe fails.

The decoder-surface probe subsequently passed a live API 36 TV emulator trial: video transferred to the receiver, returned to the source on closure, and refused attachment while paused. The decoder was stopped afterward. This proves the controlled two-app mechanism, not a YouTube integration. See `video-probe/README.md` for evidence and remaining limits.

The thumbnail-card change passed release build/lint and six presentation-state tests. Artwork rendering and session-lifetime behavior still need verification on the head unit; this revision is included in the installed task-lifecycle build. A paused YouTube title retained the card; this session supplied no artwork, so it showed the neutral play placeholder.

## Maps task lifecycle, 2026-10-31

The dock previously called the embedded launch function. Both the dock and app drawer now open the existing Maps task on display 0. Returning Home reuses that same task on the existing panel display, with no new launch intent if a running Maps task exists. Its task ID is remembered and checked against the currently running Maps tasks. An unknown duplicate set produces a recovery message instead of guessing which task owns navigation or launching another copy. No existing task is deleted.

Android 10's `startActivityFromRecents` accepts the exact task ID and target display. Explicit fullscreen window mode exits Maps' automatic PiP mode. The host task is brought forward after embedding because this API otherwise foregrounds the system Home stack. Ordinary backgrounding retains the ActivityView; graceful host destruction attempts to move Maps to display 0 before releasing it. Abrupt process death, force-stop, APK replacement, and vehicle sleep are not covered by that graceful cleanup. Before replacing the APK during testing, Maps was moved to display 0.

A separate app-icon entry activity redirects to the explicit HOME intent. This avoids creating a standard Road Home task alongside its Home task, which was observed during direct app launches. `MainActivity` remains the Home component, preserving the existing default-home selection.

Hardware observations:

- Maps task 656, ActivityRecord e49665b, and process 8959 survived repeated fullscreen/panel transitions and the launcher updates. The active route continued to arrival. One running Maps task was present throughout the captured trials.
- A direct-stack-move experiment retained Maps' pinned mode and distorted the panel. It was replaced with task-specific restoration and explicit fullscreen mode.
- Before the entry-point fix, there were two Road Home tasks and two virtual displays. The installed final build showed one Home MainActivity (task 666), Maps task 656 on display 44, and no extra standard MainActivity.
- A final fullscreen/Home/YouTube cycle and visual check of the last build remain pending. Earlier transition screenshots showed black or clipped regions, so smooth rendering is not yet established. The rear camera activated, then the head unit became unreachable before final validation.
- YouTube task 641 and its selected video survived the updates. Captures showed STATE_PAUSED, an unmuted music stream at 10/30, no muted or ducked players, and initially retained YouTube audio focus. This does not diagnose the reported silent playback after vehicle resume. No volume, focus, or MCU-mute workaround was applied.

The installed artifact is `app/build/outputs/apk/release/road-home-px6-task-fix.apk`. Release unit tests (17 total), build, lint, and platform-certificate verification passed. The five new task-selection tests cover missing, existing, stale, remembered duplicate, and ambiguous duplicate cases. Android task movement and vehicle suspend/resume require hardware validation; the API 36 TV emulator cannot exercise this Android 10 system ActivityView integration.

## Offline development fallback

When the PX6 is disconnected, continue in the dedicated Android 10 AOSP emulator instead of treating the whole task as blocked. The [offline fixture guide](task-fixture/README.md) documents its setup, build variant, signing, and repeatable task/media scenarios. The launcher and its real ActivityView successfully ran at 1280 x 720 and 240 dpi. The snapshot timeout is repaired with a boot-time per-display-focus overlay in this AVD's private image copy. Snapshots, taps, screenshots, and fullscreen/Home transitions work. The emulator reproduced a black Maps panel on a Home intent without onStart; that path now restores the existing Maps task and passed a live repeat of the failing sequence. See the fixture guide for reproducible setup and validation limits.

The subsequent media-session change is built locally and has not been installed on the PX6. A new Android 10 Robolectric test first failed because a paused video's newly arriving title was ignored while Spotify was selected. The panel now subscribes to all active media sessions. Each subscription owns a distinct Android callback, so removing one session does not disable callbacks for the others. Tests cover the title arriving without a session-list change, the card staying paused, clearing the title, duplicate controller wrappers, session removal, and cleanup on stop. These checks are separate from live emulator transition tests and do not establish that the vehicle wake/audio issue is fixed.

## Real Google apps integration, 2026-11-01

The separate [Google apps emulator](task-fixture/GOOGLE-APPS.md) now boots with
current Maps, YouTube, and Play services. Live video and map tiles work together.
Real YouTube testing found and fixed an Android 10 system_server crash caused by
moving a pinned stack onto an empty virtual display. Video embedding and Open
YouTube now restore the exact remembered task in fullscreen mode instead of
moving the stack or creating a second task. A paused YouTube session remains
selected when YouTube deactivates it in the background, while its task and title
remain available. These changes have not been installed on the head unit.

### Maps display preservation

The current emulator build keeps the map display awake while Road Home is hidden.
It also preserves Maps tasks when the launcher's virtual display is removed during
APK replacement, then adopts the existing task on Home. Fullscreen uses display 0
so the system Home key continues to work. A late task-removal callback now checks
for a surviving Maps task before covering the panel with a closed-app message.
These changes supersede the earlier map-display destruction behavior above.
They have not been installed on the PX6. See the [real-app validation notes](task-fixture/GOOGLE-APPS.md).

### Maps transitions

Maps uses a short 160 ms eased crossfade between its panel and fullscreen layouts.
A temporary, touch-blocking overlay holds an unscaled in-memory screenshot while
the existing task moves once underneath. It fades away after both Maps buffers
reach the destination width, with a 900 ms maximum wait. The image is drawn once
and its cached render node supplies the alpha animation. This avoids stretching
text and controls or redrawing clipped bitmaps on every animation frame.
The frame check observes buffer geometry, not whether map tiles finished loading.

The Android 10 screenshot overload excludes secure content. Captures are never
saved or transmitted. The platform-signed app declares SYSTEM_ALERT_WINDOW for
this brief overlay, releases it on completion/destruction, and skips the animation
when Android animators are disabled. Capture/window failures fall back to the
normal task move. This change is emulator-tested, not yet installed on the PX6.
