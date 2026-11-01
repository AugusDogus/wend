# Live Maps window transition investigation

Tested November 1, 2026 on the Android 10 (API 29) Google-apps emulator,
1280 × 720, with platform-signed Road Home and real Google Maps. No head-unit
changes. The emulator uses software rendering, so these observations do not
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

## Next experiment

Test a bounded Maps task on the main display, with the launcher arranged around
it. First verify that its bounds changes produce a remote animation target and
that Home, touch routing, navigation continuity and background apps still work.
Then animate position and crop using a surface transaction, coordinating the
final layout change with the app's new buffers. Avoid nonuniform scaling.
This architecture is a candidate, not a validated implementation. A live crop
animation is also different from asking Maps to reflow its UI on every frame.

## Android source references

- [RemoteAnimationTarget](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/view/RemoteAnimationTarget.java)
- [ActivityTaskManagerService: resizeStack and resizeTask](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)
- [ActivityView: surfaceChanged resizes its virtual display](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r47/core/java/android/app/ActivityView.java)

Local recordings and dumps are in ignored `build/motion/maps-live-resize-*` and
`build/live-transition/`. They demonstrate experimental failures, not a shipped
visual improvement.
