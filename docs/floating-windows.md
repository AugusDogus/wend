# Floating windows

Tested November 7, 2026 on the offline Android 10 fixture emulator
(`road-home-api29`, 1280 × 720, 240 dpi) with the `emulator` variant. Real Google
Maps, real YouTube, and the PX6 were not available for these checks.

## Maps no longer shrinks into system PiP on Home

**Symptom.** With Maps fullscreen (dock button) and navigating, Home showed
Android's small PiP window before Road Home moved Maps back into its panel.

**Cause.** Home calls the navigation app's `onUserLeaveHint`, and Maps (or the
fixture's automatic PiP toggle) then calls `enterPictureInPictureMode`. Android
pins the task on display 0 before Road Home's Home intent arrives, so
`MapsHomeAnimation` finds no closing Maps target and falls back after 700 ms.
Reproduced before the fix: a `mode=pinned` stack for the Maps task, `PiP=true`
from the fixture, `No Maps Home animation target; using the panel handoff`, and
SystemUI's PiP menu activity pulled into the Maps task.

**Fix.** Road Home owns the Maps window, so `MapsPictureInPicture` sets the
`PICTURE_IN_PICTURE` app-op to `MODE_IGNORED` for the Maps package the first
time `EmbeddedMaps` manages Maps. This is the same switch as Settings > Apps >
Special app access > Picture-in-picture. In AOSP android-10.0.0_r47,
`ActivityTaskManagerService.enterPictureInPictureMode` asks
`ActivityRecord.checkEnterPictureInPictureState`, which returns false unless
`checkEnterPictureInPictureAppOpsState` sees exactly `MODE_ALLOWED`. The app gets
`false` back, no exception, and stays fullscreen. The change is logged once with
the package and uid. A choice already made in Settings is left alone. If the
call fails, the error says Maps and its route are unaffected and how to turn PiP
off manually; Maps still opens and returns as before.

The app-op persists outside Road Home. After removing Road Home, re-enable PiP
for Maps in Settings, or with `adb shell appops set <maps package> PICTURE_IN_PICTURE allow`.
The archived `windowed` variant does not set it.

Better alternatives were not found: Home is dispatched before any launcher code
runs, and undoing PiP after `onActivityPinned` is exactly the visible flash.

## Live video floats above fullscreen Maps

When Maps opens fullscreen (dock or app drawer) while the embedded video is live
and playing, the video keeps playing in a 288 × 162 dp window above Maps:
16:9, 12 dp corners, a hairline edge and soft shadow, 16 dp from the screen
edges (below the status bar). Paused, thumbnail-only, or unavailable videos keep
today's behavior. The `layout` preference `floatingVideo` (default true) turns
it off; `floatingVideoCorner` remembers the last corner.

- **Enter.** The video moves from its card to its corner over 280 ms with
  `live_window_motion`, starting as the Maps expansion starts.
- **Drag.** It follows the finger 1:1, clamped on screen. Release projects the
  momentum (a normal 0.998/ms scroll deceleration) and snaps to the nearest
  corner over 280 ms. A touch during the snap catches it where it is drawn.
- **Default corner: bottom right.** In fullscreen Google Maps screenshots
  (`build/maps-rendering/final-contact-sheet.jpg` in the main checkout), the
  instruction bar spans the top, so either top corner would hide the next turn.
  Bottom left holds the Street View thumbnail, and in guidance the trip sheet.
  Bottom right covers only small step and location buttons, and is the passenger
  side, away from the driver's line of sight. Those were route-preview
  screenshots, not live guidance, so check the choice on the PX6.
- **Home** returns it to the card once Road Home's window has a surface again,
  concurrently with the Maps return animation. Playback never stops.
- **Tap** expands the live video to fullscreen above Maps, with YouTube's own
  touch input. Home then returns the video to its card and Maps to its panel.
- **Pause** keeps the last frame floating. Home then shows the thumbnail.
- **Removed cleanly** (160 ms fade, video display powered off as before) when
  the session ends or clears its title, or another app's task is created or
  brought to the front. Maps, Road Home, and the video's own display are allowed.

### How it works

There is no system PiP, task move, new task, or Play command for the video.
`LiveVideoWindow` reparents the ActivityView's own root surface into a full-screen
`TYPE_APPLICATION_OVERLAY` window and places it with `SurfacePlacement` (uniform
scale, offset, crop to the decoder bounds from `VideoSurface`), plus a
SurfaceFlinger corner radius scaled into layer space. `FloatingOutline` adds
color layers for the edge and shadow in the same transactions, so they never lag
a drag. `OverlayTouchRegion` limits the overlay's touchable area to the floating
rectangle through Android 10's internal-insets listener, so Maps keeps every
other touch; the video's input portal stays empty until fullscreen.

`EmbeddedDisplayLifecycle` (formerly `MapDisplayLifecycle`) now covers both
displays. Maps keeps its display awake and hidden while Road Home is stopped.
The video keeps the stock power-off, except while presented elsewhere: host
surface changes then neither power it off nor reparent it, and the missed state is
applied when the presentation returns to Road Home.

Maps signals `MapPanel.Covering` once, as it starts covering Road Home. On a
slow expansion (Maps' buffers miss the 900 ms readiness deadline) the frozen
cover is still up, so the cover blanks the card the video left; otherwise the
cover would show a second, frozen copy. That path signals after the blocking task
move so the float's first frames are not starved.

After a tap to fullscreen, Android 10 sends Home to the video's virtual display.
`LiveVideoWindow` therefore routes the Home key to Road Home while fullscreen and,
after a dismissal, until Road Home is shown again. Found on the emulator: without
this, Home did nothing after the title was cleared from fullscreen.

Reusable for floating Maps over fullscreen video: `FloatingPlacement` (corners,
1:1 drag, momentum release), `FloatingDrag` (tap vs. drag), `SurfacePlacement`,
`FloatingOutline`, `OverlayTouchRegion`, and `EmbeddedDisplayLifecycle`.

## Validation

Release and emulator unit tests (79 each), release/emulator/windowed assembly,
and release lint pass. New JVM/Robolectric tests cover the app-op (allowed,
already disabled, missing Maps), corner geometry, 1:1 clamped drags, momentum
projection and snapping, tap vs. drag vs. cancel, placement interpolation, the
floating window's readiness timeout, Home before readiness, single dismissal,
and Home routing after a dismissal.

On the emulator (evidence under `build/evidence/`, git-ignored):

- PiP flash reproduced (`maps-pip/before-*`) and fixed (`maps-pip/after-*`): no
  pinned stack after Home, `Returning live task 49`, and task 49, instance
  `134e6c06478`, route marker, and automatic PiP preserved.
- Video playing in the card, dock Maps, floating video advancing (frame 55 to 109
  in 2.25 s) (`floating-video/02`, `03`, `08`, `09`).
- Drags and snaps to all four corners (`05-*`), Home back into the card still
  playing (`04-*`), tap to fullscreen and Home (`06`, `07`).
- Two recorded cycles (`20-cycle-1.mp4`, `21-cycle-2.mp4`) including the
  remembered corner. Tasks (Maps 49, video 72, Home 71), process IDs, and the two
  Road Home virtual displays stayed constant; no floating layers or overlay
  windows remained (`cycles-*.txt`).
- Paused while floating keeps its last frame (`10-*`), Home shows the thumbnail
  (`11`), a paused card does not float (`12`), another app dismisses it (`14-*`),
  clearing the title dismisses it and Home still works (`16`, `17`), and the
  preference off keeps today's behavior (`18-*`).

### Limits

- The map fixture has no SurfaceView, so `MapFrame` never reports ready and every
  expansion takes the slow (cover) path. The live-expansion entry path with real
  Maps tiles was not exercised.
- Software rendering stalls the main thread as Road Home stops, so recordings show
  the entry motion skipping its first frames. Not a head-unit performance claim.
- On Home, Maps' existing settle cover freezes the screen, including the card,
  until Maps' panel buffers settle (always the 900 ms deadline on the fixture).
- The fullscreen video shows under the status bar that fullscreen Maps keeps.
- Pausing used `adb shell media dispatch pause`: the device tool's media key was
  dropped by the focused fixture window. The preference was toggled with a
  temporary debug receiver, removed before committing. Title clearing was tested
  from the tapped fullscreen state, because the floating window gives the app no
  input by design.
- The map fixture now declares `screenLayout` in `configChanges` like Google Maps;
  without it, every panel/fullscreen move recreated the fixture and lost its route.
  The installed fixtures were signed with another debug key and had to be
  uninstalled and reinstalled.
- Not verified: real Google Maps or YouTube, live guidance, and the PX6.
