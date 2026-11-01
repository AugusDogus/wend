# YouTube preview expansion

The media action and app drawer share the same opening path. A visible, playing
preview expands over 260 ms using the launcher's window ease-out curve. Paused
thumbnails, missing geometry, disabled animations, and previews that are still
preparing use the existing direct task-opening path. Nothing sends Play.

## Live surface ownership

`SurfaceExpansion` temporarily reparents our ActivityView container under a
transparent application-overlay window. It waits for that window's first
committed frame before starting motion. An extra SurfaceView introduced missing
early frames in testing, so the live container attaches directly to the owned
window surface.

The scale is uniform on both axes. The initial crop and transform exactly match
the video card, including videos with a different aspect ratio. At the end the
transform is identity and the crop covers the full virtual display. If YouTube
is showing its watch page, the crop reveals that interface around the video.
The task and virtual display are never resized on animation frames. This path
does not capture pixels, read stream URLs, or use a bitmap transition.

The same existing task moves to display 0 after the animation. Its live surface
stays visible until the move completes; removing it first exposed Home for a
frame. Cleanup restores our container's original parent and removes the overlay.
Home or backgrounding cancels a pending expansion without a delayed app launch.
Repeated opening requests are coalesced, and missing window readiness falls back
to a direct open after 700 ms.

## Input and lifecycle

The embedded input portal is disabled during motion. After a successful task
transfer, the now-empty ActivityView is released. Keeping it alive left duplicate
YouTube input-window entries on the virtual and physical displays in the Android
10 emulator, making fullscreen controls unresponsive. Releasing the empty host
cleared the old assignment. Returning Home hosts the existing task in a new
full-resolution ActivityView; it does not relaunch the video.

Opening an external app explicitly suspends embedding and hides native Maps
corner masks. Android can keep Home started while its virtual display exists,
so `onStop` alone is insufficient. A Home intent restores these hosts even when
there is no new `onStart`; failed launches restore them immediately.

## Validation

- Release and windowed builds, unit tests, and lint pass. Tests cover exact card
  and display endpoints, offset watch-page geometry, portrait-video proportions,
  crop containment, cancellation, repeated requests, and disabled animations.
- Real YouTube on the Android 10 emulator expanded beside real Maps. Final
  recordings show live video throughout expansion without the earlier blank
  startup or Home flash at handoff. The final repeat retained YouTube task 131.
- Fullscreen Pause worked after transfer, with one YouTube input-window entry
  on display 0 and no remaining virtual display. Opening a paused video did not
  start playback. Maps corner masks did not remain over fullscreen YouTube.
- Local recording: `build/motion/youtube-expansion-final.mp4`. Earlier recordings
  are diagnostic prototypes. Software-rendered emulator frame rate does not
  establish head-unit performance.

Hardware animation, ignition/audio behavior, non-fullscreen watch-page playback,
and portrait-video playback still require device validation. Geometry for those
layouts is unit-tested. The return-to-Home video path is not animated by this change.

Build `f5c5c34` and its matching decoration overlay were installed on the PX6 on
2026-11-01, preserving app data. The installed APK hash matched the signed artifact.
Maps fullscreen/Home ran on hardware; an initial stale map surface extending into
the sidebar cleared after restarting Maps. The final Home screenshot showed the
correct panel bounds. This deployment did not measure YouTube animation performance.
