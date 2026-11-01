# Live Maps window transitions

Tested November 1, 2026 on the Android 10 (API 29) Google-apps emulator,
1280 × 720, with platform-signed Road Home and real Google Maps. The initial
experiment was later deployed to the PX6; the correction below supersedes it. The emulator uses software rendering, so these observations do not
establish head-unit performance.

## What works

Android 10 has `RemoteAnimationAdapter` and `SurfaceControl.Transaction`.
A remote animation receives a window-manager-owned surface handle (a leash),
which can control live app surfaces rather than a bitmap preview.

A control launch of Android Settings using `ActivityOptions.makeRemoteAnimation`
produced opening and closing targets for Settings and Road Home, including
valid leashes and 1280 × 720 bounds. This confirms the runner implementation
and emulator permissions worked.

## Why this is not a drop-in replacement

Passing the same remote adapter to `startActivityFromRecents` while moving the
existing Maps task between its embedded virtual display and display 0 produced
no start or cancellation callbacks in either direction. The task moved, but the
launcher received no animation leash. This is an observation of our current
path, not a claim that all cross-display remote animation is impossible.

`IActivityTaskManager.resizeStack(..., animate=true, ...)` explicitly rejects
non-pinned stacks in Android 10. Its built-in animated resize is for PiP.
`resizeTask` can change task bounds but does not promise synchronized app redraws
or a compositor animation.

## Live resize prototype

A separate prototype resized the actual ActivityView and its virtual display
while expanding the panel, then moved the same Maps task to display 0. No bitmap
was stretched. The 280 ms trials showed black regions, delayed buffers and
abrupt layout changes. Fixing parent layout interference did not eliminate the
artifacts. A slowed 1,200 ms trial reached the correct fullscreen layout on the
virtual display before the task move, confirming that real resizing is possible.
A mid-animation dump showed changing display and Maps window widths.

This does not isolate every artifact to Maps, Android or the prototype. It does
show that replacing the bitmap animation with per-frame display resizing is not
yet a satisfactory fix. The experimental code was removed and the existing
unscaled crossfade restored.

## Implemented experiment: main-display Maps

The opt-in `windowed` build keeps the real Maps task in a bounded freeform window
on display 0. The standard release still uses ActivityView. No head-unit install
has been performed for this experiment.

Changing that task to fullscreen produces an Android 10 remote animation target
for `TASK_CHANGE_WINDOWING_MODE` (27), including a live SurfaceControl leash and
start bounds. Road Home animates the leash's position and crop over 280 ms with
an ease-out curve. It does not scale Maps pixels or ask Maps to relayout every
frame. Maps lays out once at the destination size; the compositor reveals that
live content through a growing window. Controls can change layout at handoff.

An unscaled, stationary screenshot covers preparation while Maps produces its
new buffers. The cover disappears before live motion begins. Readiness requires
both the Maps window and tile buffers at the destination width, accounting for
freeform shadow insets. A 900 ms deadline completes the window change without
motion if readiness fails. The standard ActivityView build retains its crossfade.
Android's disabled-animation setting bypasses the custom animation.

The display animation definition is registered only for expansion and removed
when its callback arrives, when canceled, or after the missing-callback deadline.
The runner finishes unexpected targets immediately. It never registers normal
app-launch transitions. Cleanup removes screenshot and corner overlay windows.

### Returning Home

An activity-local remote animation definition handles Home's task-open,
front/back, and wallpaper transitions. It requires both an opening Road Home
target and the requested closing Maps task. Other transitions finish immediately;
the registration cannot intercept app switches that do not involve Road Home.

The original fullscreen Maps surface moves and crops into the panel over 240 ms
with the same ease-out curve as expansion. Its pixel scale stays at one. The Home
leash must be explicitly shown: Android supplies it with the hidden flag even
though its alpha is one. Without that step the shrinking map exposes black.

At the final geometry, a stationary screenshot briefly covers the new panel
layout. The task is bounded **before** acknowledging the closing animation, which
avoids hiding/recreating Maps' SurfaceView and leaving an old tile surface outside
the panel. The cover disappears when the new buffers settle, without an alpha
animation. Maps' own controls still reflow at that final handoff. This is a live
window animation, not continuous application relayout.

Repeated Home requests are coalesced. Leaving the launcher cancels pending task
moves and removes the cover, and finishing/canceling the remote callback is
idempotent. Reopening fullscreen during a return brings the same task forward
without waiting for a nonexistent mode-change callback. If Android provides no
Home target within 700 ms, the panel uses a direct covered handoff. Disabled
animations restore immediately. No snapshot is stretched, saved, or transmitted.

Freeform titlebars are removed by a separate reversible framework resource
overlay. It must be compiled against the device's exact `framework-res.apk`,
not the SDK framework: private resource IDs differ. A mismatched prototype
caused Maps' decor inflation to crash. The build now embeds the framework hash;
the launcher checks it before enabling the overlay. The overlay affects all
freeform titlebars while enabled. Standard fullscreen windows are unaffected.

The freeform task stays below Android's status bar, so this variant reserves the
status-bar height and omits the duplicate dock clock. App drawer and settings
use application-overlay windows to appear above the freeform Maps task. Only
the sidebar-facing Maps corners are masked.

## Validation and limits

Release and windowed unit tests passed (38 each), along with their builds and
lint. The earlier fixture suite passed 32 tests. New tests cover missing Home
callbacks, cancellation before a task move, superseded destinations, disabled
animations, and return geometry. Geometry tests cover both panel sides and exact
final bounds. Buffer tests accept real freeform shadow insets and reject stale
tile widths.

Emulator trials preserved Maps task 93 and its ActivityRecord across fullscreen,
Home, panel-side changes, launcher APK replacement, and a YouTube round trip.
Live expansion was recorded on both sides. Cold trials sometimes hit the buffer
deadline and completed without motion; subsequent trials animated. This remains an
experimental architecture, not a claim of consistently smooth hardware behavior.

A rapid fullscreen/Home trial exposed duplicate restoration requests from Home
lifecycle callbacks. A retained old Maps tile surface then protruded into the
freeform shadow area. Restoration now skips an already foregrounded task at the
same bounds, and Home cancels any expansion even when delivered through
`onNewIntent`. After relaunching Maps to clear the already leaked surface, three
rapid trials and a YouTube round trip retained task 103 without the leak or
repeated bounds commands. A SurfaceFlinger dump showed one tile layer at the
correct panel bounds. Both panel sides and both configured sizes were checked;
no transition cover remained afterward. This does not establish long-duration
stability.

Active navigation continuity has not been verified in this variant: Maps returned
“Something went wrong” when calculating the test route. Keeping the same task is
verified separately from maintaining an active route. Vehicle suspend/audio
and head-unit performance remain unverified.

The reverse-animation emulator trial kept Maps task 108 through returns on both
panel sides, repeated Home presses, and a YouTube round trip. The corrected trial
showed the launcher behind the shrinking map, no leaked tile strip afterward,
and no leftover transition/corner overlays while YouTube was foregrounded.
The first two prototype recordings contained a hidden Home surface and a bad
handoff order; they are retained as failure evidence, not demos.

See the [setup and rollback instructions](../README.md#experimental-live-maps-window).

## Android source references

- [RemoteAnimationTarget](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/view/RemoteAnimationTarget.java)
- [ActivityTaskManagerService: resizeStack and resizeTask](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)
- [ActivityView: surfaceChanged resizes its virtual display](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/app/ActivityView.java)

Local recordings and dumps live in ignored `build/motion/` and
`build/live-transition/`. `maps-live-verified.mp4` captures the final expansion, Home return, and drawer;
`maps-home-visible.mp4` and `maps-home-left.mp4` capture the reverse animation.
`maps-live-resize-*` captures the earlier failed per-frame resize approach.

## Fullscreen layout correction

The freeform experiment caused a UI regression on the PX6: its OEM status bar
and back control appeared above Home, the whole layout moved down, and the dock
clock was hidden. Android 10 `DisplayPolicy.updateSystemBarsLw` forces system
bars while a freeform stack is visible, irrespective of Home's immersive flags.
Removing freeform captions does not remove this system bar.

Use the standard `release` build, which retains the embedded ActivityView.
Its dock clock and full-height layout remain intact; the dock now has an opaque
background to distinguish it from the media sidebar. No ROM patch or global
immersive override is needed. Uninstall our experimental
`dev.augie.roadhome.windowdecor` overlay when migrating from `windowed`.

`EmbeddedMapMotion` resizes the embedded display once beneath a stationary
cover, waits for Maps' window and tile buffers, then expands the owned live
surface using `SurfaceExpansion`. Maps stays at pixel scale one throughout:
only position and crop animate. The exact existing task moves to display 0 at
the end. A cold render can miss the 900 ms preparation deadline; that path moves
the task under the original cover and waits for the fullscreen frame with a
bounded deadline. It does not leave an overlay indefinitely. Maps' own controls
still change layout between panel and fullscreen sizes.

Returning Home uses the activity-local `MapsHomeAnimation` to shrink the live
fullscreen task before returning it to the embedded display. This differs from
the earlier unsuccessful attempt to pass a remote adapter directly to a
cross-display `startActivityFromRecents` call. The input portal and root surface
are restored when Maps returns, including when Home never received `onStop`.
Queued buffer callbacks from a canceled expansion cannot start a newer request.

Validation: release and windowed builds, lint, and 46 unit tests per variant
passed. The new geometry case verifies Maps stays unscaled and its moving edge
meets the display edge throughout expansion. Android 10 emulator screenshots
verified the restored full-height panel, dock clock, insets, and separation.
Repeated Maps fullscreen/Home trials retained task 108 and exercised the live
Home return. One cold expansion hit the readiness fallback; subsequent warm
expansions used the live surface. This is not a head-unit performance claim.

Real YouTube playback beside Maps, Maps layer-button input, Open YouTube, and
returning Home were also exercised after the correction. No playback command
was added to embedding.

The PX6 was offline during this correction. Its last installed launcher is the
regressed `windowed` build; installation and hardware verification of the
corrected `release` remain pending reconnection.
