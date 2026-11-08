# Floating windows

First built and tested November 7, 2026 on the offline Android 10 fixture
emulator (`road-home-api29`, 1280 × 720, 240 dpi) with the `emulator` variant,
before the floating-window lane was combined with the launcher UI lane. The
fixes of November 8 were checked on the combined tree, on the real Google Maps
and YouTube emulator (`road-home-google-api29`) with the `release` variant; see
[validation](#validation). The PX6 was not available for either pass.

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
time each launcher activity manages Maps. This is the same switch as Settings > Apps >
Special app access > Picture-in-picture. In AOSP android-10.0.0_r47,
`ActivityTaskManagerService.enterPictureInPictureMode` asks
`ActivityRecord.checkEnterPictureInPictureState`, which returns false unless
`checkEnterPictureInPictureAppOpsState` sees exactly `MODE_ALLOWED`. The app gets
`false` back, no exception, and stays fullscreen. Each change is logged with the
package and uid. Only "off" is left alone: Road Home cannot tell a driver's
"allowed" in Settings from the untouched default, so if PiP is turned back on
there, Road Home turns it off again the next time the launcher starts managing
Maps. If the call fails, the error says Maps and its route are unaffected and
how to turn PiP off manually; Maps still opens and returns as before.

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
  corner over 280 ms. While it snaps, its touch area follows it frame by frame,
  so a touch where it is drawn catches it there, and the app below never gets
  that touch. Before November 8 the touch area jumped to the destination at
  release, so a touch on the moving window outside it reached Maps.
- **Default corner: top right.** In real Google Maps live guidance at
  1280 × 720 landscape, the next-turn card sits top left, the trip sheet bottom
  left, and the compass, search, and sound buttons bottom right. The first
  default, bottom right, came from route-preview screenshots and covered search
  and sound during guidance; top right covered nothing, and Maps' search opened
  beside it (device report RH-2: `s2n-nav-corners-grid.png`,
  `s2n-01-nav-full-video-float.png`, and `s2n-05-search-closed.png` under
  `build/validation/real-apps-api29/` in the main checkout). A corner the driver
  drags it to is remembered and wins over the default. Check it on the PX6.
- **Home** returns it to the card once Road Home's window has a surface again,
  concurrently with the Maps return animation. Playback never stops.
- **Tap** swaps: the video takes the screen and Maps floats above it (see
  [swapping](#swapping)). A floating window takes no touches while it enters, and
  for 500 ms after it comes to rest it takes a tap without acting on it; drags
  still work. A second tap on the dock's Maps button, landing where the video has
  just arrived, therefore keeps Maps fullscreen instead of swapping (device report
  BUG-4).
- **Pause** keeps the last frame floating. Home then shows the thumbnail.
- **Removed cleanly** (160 ms fade, video display powered off as before) when
  the session ends or clears its title; when playback stops, fails, or loses
  its playback state (`STOPPED`, `ERROR`, `NONE`, not `PAUSED`), because YouTube
  then draws its stopped, up-next, or ad screen into the window (device report
  RH-7); or when another app's task is created or brought to the front, also
  while a tapped floating video is still growing to fullscreen. Maps, Road Home,
  and the video's own display are allowed. Home afterwards works as usual: Maps
  returns to its panel, and the card shows the stopped video's thumbnail.
- **Not floated** when another app paused Road Home while Maps was preparing to
  go fullscreen: the video then stays in its card rather than floating above
  that app.
- **Maps fails to go fullscreen** (its task move is refused after the video has
  started floating): Road Home stays visible with the panel's error, and the
  video returns to its card.

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
move so the float's first frames are not starved, and never after a move that
failed. The live expansion signals as it begins, before the move; if the move
then fails, `Covering.abandoned` returns the video to its card
(`EmbeddedMaps.CoverOnce` keeps that order).

Once the video is fullscreen and receives touches, Android 10 sends Home to the
video's virtual display. `LiveSurfaceWindow` therefore routes the Home key to Road
Home while a display is shown whole and,
after a dismissal, until Road Home is shown again. Found on the emulator: without
this, Home did nothing after the title was cleared from fullscreen.

## Live Maps floats above fullscreen video

When the live video opens fullscreen (the "Open" action under its card), Maps
keeps running live in a small window above it. The `layout` preference
`floatingMaps` (default true) turns it off; `floatingMapsCorner` remembers its
corner, default bottom right (the passenger side).

- **Size.** The floating video's area (288 × 162 dp) in the panel's own shape:
  232 × 201 dp for the 70% panel, 215 × 217 dp for 60%. The panel's display is
  never resized; the compositor scales the whole panel display, so Maps keeps
  its panel layout and every control stays where the driver expects it.
- **Enter.** Maps lifts out of its panel into its own overlay without moving,
  the video expands from its card beneath it, then Maps shrinks into its corner
  (280 ms each). Moving both at once uncovers parts of the empty panel.
- **Drag, snap, outline, corners, catch mid-snap, tap grace after entering:** as
  for the floating video.
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
- **Maps closed while floating** (its task removed): the panel's "Maps was
  closed" message and its Open Maps button stay after Home returns Maps' empty
  display to the panel. The same message appears when Maps is gone as a tapped
  floating video becomes fullscreen, instead of Maps floating. Road Home does
  not relaunch Maps on its own in either case.

## Swapping

Tapping a floating window brings that app fullscreen and floats the other one
when its preference allows (the video also only while playing). Neither task is
recreated or duplicated, no Play command is sent, and the video's task, virtual
display, and decoder stay the same.

- **Floating video over fullscreen Maps (tap the video).** The video grows from
  its corner to fullscreen above Maps (the existing tap motion). Fully hidden
  under it, Road Home comes forward first and, after its next frame, the Maps
  task returns from behind it to its panel's virtual display, the same order as
  Home. Once Maps has laid out at the panel's size (`MapFrame`, 900 ms at most),
  it emerges at its corner: 90% to 100% size and 0 to 1 opacity over 220 ms
  with `window_ease_out`. About 0.8 s from tap to floating Maps on the emulator. With `floatingMaps` off,
  Maps just waits in its panel and Home shows it there. The video is now above
  Road Home rather than Maps, so the status bar fullscreen Maps kept disappears.
- **Floating Maps over fullscreen video (tap Maps).** Maps grows from its corner
  until its whole panel display shows at pixel scale one, centered (never
  enlarged, so no stretched frames). A frozen frame then holds while, beneath
  it, Maps returns to its panel, the video hides, Maps' display is laid out at
  the full screen size, and the existing task moves to display 0. When Maps'
  buffers show the new width, the frozen frame crossfades away over 160 ms and
  the video emerges at its corner. A paused video, or `floatingVideo` off,
  returns the video to its card instead. Without a frozen frame (animations
  off, or the screenshot failed), the video still emerges only once the task
  has moved; before November 8 it emerged over Road Home first.

Why the Maps direction differs: Maps' display is panel-sized, so fullscreen Maps
needs one new layout, which must happen out of sight. The video direction hides
it under the live video. In the Maps direction, Maps is the one growing, and the
dock's live expansion cannot be reused as is: it reveals Road Home around Maps,
whereas here the video's overlay must stay visible below Maps until Maps covers
the screen, and yet end above Maps. The swap therefore uses the same
frozen-frame-and-crossfade handoff as Maps' other transitions (`MapTransition`),
held until the task has moved. Centering Maps first keeps the map's center where
fullscreen Maps puts it, so the crossfade barely ghosts.

Also found on the emulator: when Maps moved to its panel display while still
visible, the fixture's map SurfaceView sometimes kept its fullscreen-sized buffer
in the panel (seen twice in about 15 swaps, once inside floating Maps). Moving it from
behind Road Home, as Home does, fixed it: 8 of 8 swaps then showed the panel-size
surface with no readiness timeout (`swap-surface/run-*.png`).

Found on the emulator: moving Maps to display 0 straight from its panel-sized
display left Road Home resumed and visible behind fullscreen Maps (no pause or
stop in the activity event log), so Home had no opening Home window for
`MapsHomeAnimation` and fell back to the panel handoff. Laying Maps out at the
full screen size first, as the dock expansion does, restored the normal stop and
the live Home return.

Home from any of these states returns Maps to its panel and the video to its
card. Home during a swap cancels it: a pending task move never runs, a frozen
frame is removed, and a hidden video returns straight to its card. Without the
live map motion (its hidden APIs unavailable), Home could also cancel a frozen
frame whose move into the panel had not run yet and leave every later Maps
request ignored; it now asks for the move again.

Changing the map width in Settings re-fits Maps' crop and input region to the
resized panel. Before, after one Home at 60%, choosing 70% left a 119 px black
strip at the panel's outer edge that took no touches, cutting off Maps' compass,
search, and sound buttons, until the next Home.

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

On the floating-window lane, before it was combined with the launcher UI lane:
release and emulator unit tests (79 each), release/emulator/windowed assembly,
and release lint passed. New JVM/Robolectric tests cover the app-op (allowed,
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

On the same lane, before the combination: release and emulator unit tests (94
each), release/emulator/windowed assembly, and release lint passed. New tests
cover the area-matched floating size, centered and scaled placements, the held,
emerging, and hide-for-corner window lifecycles before a compositor frame,
`returnOverCard`, and the held crossfade's covered/revealed order. Two of them
passed for the wrong reason until November 8: the Maps tests never reached the
"not live in its panel" check (the JVM process is not uid 1000, so the panel had
no ActivityView), and the hide-for-corner test never drew a frame. Both now run
against a drawn window and a ready panel, with a positive case beside each
negative one.

The map fixture now draws its map into a SurfaceView, as Google Maps draws tiles,
so `MapFrame` can report Maps ready and the dock's live expansion runs on the
emulator (`floating-maps/30-dock-live-expansion*`). Its "Toggle map surface"
button hides it to exercise the slow cover path.

On the emulator (`build/evidence/floating-maps/` and `build/evidence/matrix/`):

- Open video with Maps floating (`01`, `04` and sheets), Home (`03`, `05-home-v2`
  and both `-sheet` files), drag and snap of floating Maps and its remembered
  corner (`06-*`, `07`).
- Floating video tapped over fullscreen Maps (`10-swap-to-video*` before the
  return-order fix, `13-swap-to-video-v2*` after), with `floatingMaps` off
  (`12-*`), Home after it (`11`).
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
- The final build (`build/evidence/final/`): a full cycle with leak checks
  (`steps.txt`, `before.txt`, `after.txt`) and Home shortly after tapping the
  floating video (`H-home-mid-swap.png`). The device tool's Home key arrives
  0.5 to 1 s after it is sent, so that swap had already finished; Home during a
  swap is covered by code paths only.
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
- The stale map surface above is fixed for the swap by its order, not by a
  root cause in Android. Separately, early visits of the map fixture to display
  0 left extra fullscreen-sized SurfaceView layers under its window (seen with
  the dock path too; none appeared in the later runs). Both look like Android 10
  SurfaceView behavior across displays, possibly the stale-sized Maps frames
  reported in `task-fixture/GOOGLE-APPS.md`; not reproduced with real Maps here.
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
  committing; the November 8 pass toggled them through the settings sheet.
- The map fixture declares `screenLayout` in `configChanges` like Google Maps;
  without it, every panel/fullscreen move recreated the fixture and lost its route.
  The installed fixtures were signed with another debug key and had to be
  uninstalled and reinstalled.
- Not verified in the first pass: real Google Maps or YouTube, live guidance,
  and the PX6. The PX6 is still unverified.

### Fixes of November 8 on the combined tree

The combined tree (both lanes plus these fixes) passes release and emulator unit
tests (179 each), release/emulator/windowed assembly, and release lint (no
errors; 52 warnings, the same count as the combined tree's earlier report).
New or rewritten tests cover the top-right default corner, the 500 ms tap grace,
the touch area following a snap, a floated whole display never opening its task
directly when it fails, `overAnotherApp`, the floating windows' lifecycles on a
drawn window (Robolectric with its frame commit fired and its touch region read
back), Home cancelling a covered move into the panel, Maps closed under or
while floating, Maps returning beneath the video and floating, a failed move to
fullscreen never floating the video, the covered takeover's reveal order without
a cover, and which playback states end a floating video.

On `road-home-google-api29` with real Google Maps in live guidance (Redwood
Valley to Ukiah) and YouTube, release build (evidence under this worktree's
git-ignored `build/evidence/`):

- Top right covers none of the guidance controls (`device-5554/03-*`). This
  device had a remembered corner, so the default itself is covered by the unit
  test only.
- BUG-4: with the video's corner at top left, a second tap on the dock's Maps
  button 692 ms after the video started floating (the report's was 685 ms)
  kept Maps fullscreen (`device-5554/07-*`, tap times from the input
  dispatcher's queue in `07-bug4-dumpsys-input.txt`). A tap 130 ms after it
  started passed through to Maps, as before (`05-*`); one 1.03 s after it
  started, past the grace, swapped as a deliberate tap should (`06-*`).
- RH-7: `adb shell media dispatch stop` while the video floated over guidance
  closed it ("the video is in playback state 1, not paused"), left no overlay
  window or layer, and Home returned Maps live to its panel with the stopped
  card (`device-5554/08-*`, `09-*`).
- Width change: the 119 px strip reproduced on the previous build and is gone,
  with Maps' input region matching the 70% panel (`seams-f3/07-*`, `13-*`,
  `14-fixed-dumpsys-input.txt`).
- Both floating switches toggled through the settings sheet: each off keeps its
  app out of the floating window, and both back on restore it
  (`switches/switches-sheet.png`).
- Regression: Home, dock Maps with the video floating, drag to top right, both
  swaps, Home, Open YouTube with Maps floating, and Home again
  (`regression/matrix-3c1f943/`, `matrix-sheet.png`, `matrix.mp4`). Maps task
  190, YouTube task 265, and Road Home task 264 stayed the same throughout, no
  pinned stack appeared, and no floating window remained after either Home.
- Not reproduced on a device: Home cancelling a covered move (needs the live map
  motion to be unavailable), a failed task move, Maps closing while it floats,
  and another app coming forward while Maps prepares (the device tool's app
  launch arrived 1.5 s after the expansion had finished, and the existing
  dismissal closed the video, `f3b/`). These rest on the unit tests.
- Installing a new launcher APK while YouTube played in the card ended YouTube's
  embedded activity (task 253), as the README warns; YouTube was reopened from
  the dock. Move YouTube to fullscreen before replacing the APK.

### Known issue: Back is dropped in fullscreen Maps on the emulators

Device report RH-1: with Maps opened fullscreen from the dock, Back does not
close Maps' "Search along route" page; touches work. Reproduced on November 8
with this build and with `main` (4ff19bb) installed on the same emulator
(`build/evidence/rh1-back/`), so it predates the floating windows.

What the evidence shows:

- The input dispatcher delivers the key to Maps' window, and Maps' own window
  drops it: `ViewRootImpl[MapsActivity]: Dropping event due to no window focus:
  KeyEvent { ... KEYCODE_BACK ... }` (`logcat-repro-integrate.txt`,
  `logcat-repro-main.txt`).
- WindowManager disagrees with Maps: display 0's `mCurrentFocus` is Maps'
  window, and the input dispatcher's focused window for display 0 is Maps
  (`03-dumpsys-window-after-back.txt`, `02-dumpsys-input-after-back.txt`).
- The dispatcher still lists Maps' window as focused on the empty Road Home
  Maps display and on many earlier, removed virtual displays (2, 4, 8, ... 58).
  Those entries only produce the "Found window ... but it should belong to
  display 0" log lines; they do not drop the key.
- Both emulators enable per-display focus with the boot overlay
  `dev.augie.roadhome.emulator.bootfocus` (`task-fixture/README.md`); the PX6
  does not.

The likely cause, not confirmed in AOSP source here: with per-display focus,
the Road Home Maps display keeps Maps as its focused window while Maps is in the
panel. When the task moves to display 0, Android 10 reports focus per display,
so Maps' window first gains focus on display 0 and then loses it again from the
display it left, and the last report wins. Without per-display focus only the
top display has a focused window, so on the PX6 the panel display should have
lost Maps' focus as soon as the dock was touched. That is untested.

Clearing the empty display's stale input windows would not help, since the key
reaches Maps' window. No change was made: the only workarounds found (forcing
another focus change on display 0 after each move, or recreating the Maps
display) are speculative for the PX6 and would disturb navigation. Check Back in
fullscreen Maps on the PX6 first.
