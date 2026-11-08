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
- **Tap** swaps: the video takes the screen and Maps floats above it (see
  [swapping](#swapping)).
- **Pause** keeps the last frame floating. Home then shows the thumbnail.
- **Removed cleanly** (160 ms fade, video display powered off as before) when
  the session ends or clears its title, or another app's task is created or
  brought to the front. Maps, Road Home, and the video's own display are allowed.

### How it works

There is no system PiP, task move, new task, or Play command for the video.
`LiveSurfaceWindow` (formerly `LiveVideoWindow`) reparents the ActivityView's own
root surface into a full-screen
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

Once the video is fullscreen and receives touches, Android 10 sends Home to the
video's virtual display. `LiveSurfaceWindow` therefore routes the Home key to Road
Home while a display is shown whole and,
after a dismissal, until Road Home is shown again. Found on the emulator: without
this, Home did nothing after the title was cleared from fullscreen.

## Live Maps floats above fullscreen video

When the live video opens fullscreen (the "Open" action under its card), Maps
keeps running live in a small window above it. The `layout` preference
`floatingMaps` (default true) turns it off; `floatingMapsCorner` remembers its
corner, default bottom right (the passenger side, as for the video).

- **Size.** The floating video's area (288 × 162 dp) in the panel's own shape:
  232 × 201 dp for the 70% panel, 215 × 217 dp for 60%. The panel's display is
  never resized; the compositor scales the whole panel display, so Maps keeps
  its panel layout and every control stays where the driver expects it.
- **Enter.** Maps lifts out of its panel into its own overlay without moving,
  the video expands from its card beneath it, then Maps shrinks into its corner
  (280 ms each). Moving both at once uncovers parts of the empty panel.
- **Drag, snap, outline, corners, catch mid-snap:** as for the floating video.
- **Input.** Maps' input portal is empty while it floats; the overlay takes only
  the floating rectangle (drag or tap) and the video keeps every other touch. On
  return, the root goes back to its panel with the same geometry and input that
  every return from fullscreen applies (`revealSurface`), and
  `EmbeddedDisplayLifecycle` re-announces the host surface.
- **Home** returns Maps over its panel first, still above the video, then the
  video shrinks into its card beneath it; the end of the video's presentation
  hands Maps back to the panel. Returning both at once left the panel's empty
  surface showing, and returning Maps' root first hid it under the fullscreen
  video for about 100 ms (both seen in recordings).
- **Removed** without motion, as the fullscreen video is, when another app comes
  to the front (Road Home stops) or the video presentation ends.

## Swapping

Tapping a floating window brings that app fullscreen and floats the other one
when its preference allows (the video also only while playing). Neither task is
recreated or duplicated, no Play command is sent, and the video's task, virtual
display, and decoder stay the same.

- **Floating video over fullscreen Maps (tap the video).** The video grows from
  its corner to fullscreen above Maps (the existing tap motion). Fully hidden
  under it, the Maps task returns to its panel's virtual display, which brings
  Road Home forward behind the video. Once Maps has laid out at the panel's size
  (`MapFrame`, 900 ms at most), it emerges at its corner: 90% to 100% size and
  0 to 1 opacity over 220 ms with `window_ease_out`. With `floatingMaps` off,
  Maps just waits in its panel and Home shows it there. The video is now above
  Road Home rather than Maps, so the status bar fullscreen Maps kept disappears.
- **Floating Maps over fullscreen video (tap Maps).** Maps grows from its corner
  until its whole panel display shows at pixel scale one, centered (never
  enlarged, so no stretched frames). A frozen frame then holds while, beneath
  it, Maps returns to its panel, the video hides, Maps' display is laid out at
  the full screen size, and the existing task moves to display 0. When Maps'
  buffers show the new width, the frozen frame crossfades away over 160 ms and
  the video emerges at its corner. A paused video, or `floatingVideo` off,
  returns the video to its card instead.

Why the Maps direction differs: Maps' display is panel-sized, so fullscreen Maps
needs one new layout, which must happen out of sight. The video direction hides
it under the live video. In the Maps direction, Maps is the one growing, and the
dock's live expansion cannot be reused as is: it reveals Road Home around Maps,
whereas here the video's overlay must stay visible below Maps until Maps covers
the screen, and yet end above Maps. The swap therefore uses the same
frozen-frame-and-crossfade handoff as Maps' other transitions (`MapTransition`),
held until the task has moved. Centering Maps first keeps the map's center where
fullscreen Maps puts it, so the crossfade barely ghosts.

Found on the emulator: moving Maps to display 0 straight from its panel-sized
display left Road Home resumed and visible behind fullscreen Maps (no pause or
stop in the activity event log), so Home had no opening Home window for
`MapsHomeAnimation` and fell back to the panel handoff. Laying Maps out at the
full screen size first, as the dock expansion does, restored the normal stop and
the live Home return.

Home from any of these states returns Maps to its panel and the video to its
card. Home during a swap cancels it: a pending task move never runs, a frozen
frame is removed, and a hidden video returns straight to its card.

### How it works

`LiveSurfaceWindow` now presents any owned ActivityView display: the video and
Maps each have one. It adds the held entry, the emerging entry, `focus` (grow to
the whole display without input), `hideForCorner`/`emerge` (a fullscreen video
becomes a floating one while covered), and `returnOverCard`. `MapPanel.Takeover`
names the three moments of the Maps direction (covered, revealed, failed), and
`MapTransition.holdThenCrossfade` holds its frame until the task has moved.
`MainActivity` routes both directions and Home.

## Validation

### Floating video and the PiP fix

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

### Floating Maps and swaps

Release and emulator unit tests (94 each), release/emulator/windowed assembly,
and release lint pass. New tests cover the area-matched floating size, centered
and scaled placements, the held, emerging, and hide-for-corner window lifecycles
before a compositor frame, `returnOverCard`, Maps that are not live in their
panel never floating, and the held crossfade's covered/revealed order.

The map fixture now draws its map into a SurfaceView, as Google Maps draws tiles,
so `MapFrame` can report Maps ready and the dock's live expansion runs on the
emulator (`floating-maps/30-dock-live-expansion*`). Its "Toggle map surface"
button hides it to exercise the slow cover path.

On the emulator (`build/evidence/floating-maps/` and `build/evidence/matrix/`):

- Open video with Maps floating (`01`, `04` and sheets), Home (`03`, `05-home-v2`
  and both `-sheet` files), drag and snap of floating Maps and its remembered
  corner (`06-*`, `07`).
- Floating video tapped over fullscreen Maps (`10-swap-to-video*`), with
  `floatingMaps` off (`12-*`), Home after it (`11`).
- Floating Maps tapped over fullscreen video (`20-*` crossfade before the fix,
  `22-swap-to-maps-v2*` after), Home after it (`21`).
- Matrix (`steps.txt` has each step's tasks, windows, and log lines):
  both preferences on, playing then paused in fullscreen (`A-grid.png`);
  `floatingVideo` off and `floatingMaps` off (`BC-grid.png`); two recorded full
  cycles of all eight transitions (`D-cycle-1.mp4`, `D-cycle-2.mp4`); another app
  over the swapped state (`E1-other-app.png`); clearing the video title under
  floating Maps (`E2-clear-title.png`); route marker and automatic PiP through
  four swaps, dock, and Home with no pinned stack (`E3-route-pip.png`); floating
  video drag regression (`F-video-drag.png`).
- Leak checks before and after the cycles (`D-before.txt`, `D-after.txt`,
  `E3-before.txt`, `E3-after.txt`): Maps task 75 (instance `5330feab4f0`),
  video task 95 (instance `716c39772b0`), Home task 94, the same two Road Home
  virtual displays, the same process IDs including system_server, and no overlay
  windows or live, outline, or transition layers left.

### Limits

- The host was saturated during testing (load average above 30 on 32 cores,
  including the other emulator), so recordings drop frames and the main thread
  stalls, most visibly at the start of the video's expansion (a 70 to 117 ms gap
  in the animator's frame log, with or without floating Maps). Motion was judged
  from repeated recordings plus the animator's frame log. Motion still runs on
  the main thread (`ValueAnimator`); a dedicated animation thread would make it
  immune to binder calls and Road Home's own drawing on the head unit.
- The Maps swap holds a frozen frame for about 0.4 s on the fixture while Maps
  lays out at the full size and moves. Real Maps may need longer; the frame is
  bounded by the 900 ms readiness deadlines.
- Once, after a swap to the video with `floatingMaps` off, the fixture's map
  SurfaceView kept its fullscreen-sized buffer in the panel until Maps next
  changed size; two repeats were correct. Separately, every visit of the map
  fixture to display 0 left an extra fullscreen-sized SurfaceView layer under its
  window (seen with the dock path too). Both look like Android 10 SurfaceView
  behavior across displays, possibly the stale-sized Maps frames reported in
  `task-fixture/GOOGLE-APPS.md`; not reproduced with real Maps here.
- With the video's live dock expansion, Maps visibly re-lays out inside its panel
  at the reveal before widening; that is the existing expansion, not the swap.
- Software rendering stalls the main thread as Road Home stops, so recordings show
  entry motion skipping its first frames. Not a head-unit performance claim.
- On Home from fullscreen Maps, Maps' existing settle cover freezes the screen,
  including the card, until Maps' panel buffers settle.
- Pausing in the matrix used the video fixture's own button while fullscreen.
  The earlier floating-video pass used `adb shell media dispatch pause`, because
  the device tool's media key was dropped by the focused fixture window.
  Preferences were toggled with a temporary debug receiver, removed before
  committing.
- The map fixture declares `screenLayout` in `configChanges` like Google Maps;
  without it, every panel/fullscreen move recreated the fixture and lost its route.
  The installed fixtures were signed with another debug key and had to be
  uninstalled and reinstalled.
- Not verified: real Google Maps or YouTube, live guidance, and the PX6.
