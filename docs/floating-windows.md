# Floating windows

First built and tested November 7, 2026 on the offline Android 10 fixture
emulator (`road-home-api29`, 1280 × 720, 240 dpi) with the `emulator` variant,
before the floating-window lane was combined with the launcher UI lane. The
fixes of November 8 were checked on the combined tree, on the real Google Maps
and YouTube emulator (`road-home-google-api29`) with the `release` variant; see
[validation](#validation). The PX6 was not available for either pass.

## Maps no longer shrinks into system PiP on Home

**Symptom.** With Maps fullscreen (dock button) and navigating, Home showed
Android's small PiP window before Wend moved Maps back into its panel.

**Cause.** Home calls the navigation app's `onUserLeaveHint`, and Maps (or the
fixture's automatic PiP toggle) then calls `enterPictureInPictureMode`. Android
pins the task on display 0 before Wend's Home intent arrives, so
`MapsHomeAnimation` finds no closing Maps target and falls back after 700 ms.
Reproduced before the fix: a `mode=pinned` stack for the Maps task, `PiP=true`
from the fixture, `No Maps Home animation target; using the panel handoff`, and
SystemUI's PiP menu activity pulled into the Maps task.

**Fix.** Wend owns the Maps window, so `MapsPictureInPicture` sets the
`PICTURE_IN_PICTURE` app-op to `MODE_IGNORED` for the Maps package the first
time each launcher activity manages Maps. This is the same switch as Settings > Apps >
Special app access > Picture-in-picture. In AOSP android-10.0.0_r47,
`ActivityTaskManagerService.enterPictureInPictureMode` asks
`ActivityRecord.checkEnterPictureInPictureState`, which returns false unless
`checkEnterPictureInPictureAppOpsState` sees exactly `MODE_ALLOWED`. The app gets
`false` back, no exception, and stays fullscreen. Each change is logged with the
package and uid. Only "off" is left alone: Wend cannot tell a driver's
"allowed" in Settings from the untouched default, so if PiP is turned back on
there, Wend turns it off again the next time the launcher starts managing
Maps. If the call fails, the error says Maps and its route are unaffected and
how to turn PiP off manually; Maps still opens and returns as before.

The app-op persists outside Wend. After removing Wend, re-enable PiP
for Maps in Settings, or with `adb shell appops set <maps package> PICTURE_IN_PICTURE allow`.
The archived `windowed` variant does not set it.

Better alternatives were not found: Home is dispatched before any launcher code
runs, and undoing PiP after `onActivityPinned` is exactly the visible flash.

## Live video floats above fullscreen Maps

When Maps opens fullscreen (dock or app drawer) while the embedded video is live
and playing, the video keeps playing in a 288 × 162 dp window above Maps:
16:9, 12 dp corners and a hairline edge, 16 dp from the screen edges (below the
status bar). It has no shadow, as Android 10's own PiP window has none. Paused, thumbnail-only, or unavailable videos keep
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
- **Home** returns it to the card once Wend's window has a surface again,
  concurrently with the Maps return animation. Playback never stops.
- **Tap, double tap, drag off the bottom:** Android 10's PiP gestures, see
  [PiP controls](#pip-controls). A double tap swaps: the video takes the screen
  and Maps floats above it (see [swapping](#swapping)). A floating window takes no
  touches while it enters, and for 500 ms after it comes to rest it takes a tap
  without acting on it; drags still work. A second tap on the dock's Maps button,
  landing where the video has just arrived, therefore keeps Maps fullscreen
  instead of swapping (device report BUG-4).
- **Pause** keeps the last frame floating. Home then shows the card's still (see
  [the video card](#the-video-card)).
- **Stopped or ended** (`STOPPED`, `ERROR`, `NONE`) keeps floating, with its
  controls up and offering play until it plays again. Before this, it closed,
  because YouTube then draws its stopped, up-next, or ad screen into the window
  (device report RH-7); feedback was that the window then vanished and could not
  be tapped to choose another video. The dimmed controls now sit over that
  screen, and a double tap or the fullscreen control brings the video app
  fullscreen, where another video can be chosen.
- **Removed cleanly** (160 ms fade, video display powered off as before) when
  the session ends or clears its title, when it is closed (its close control or a
  drag off the bottom edge, which also pauses it), or when another app's task is
  created or brought to the front, also while a double-tapped floating video is
  still growing to fullscreen. Maps, Wend, and the video's own display are
  allowed. Home afterwards works as usual: Maps returns to its panel, and the
  card shows the video's still.
- **Not floated** when another app paused Wend while Maps was preparing to
  go fullscreen: the video then stays in its card rather than floating above
  that app.
- **Maps fails to go fullscreen** (its task move is refused after the video has
  started floating): Wend stays visible with the panel's error, and the
  video returns to its card.

### How it works

There is no system PiP, task move, new task, or Play command for the video.
`LiveSurfaceWindow` (formerly `LiveVideoWindow`) reparents the ActivityView's own
root surface into a full-screen
`TYPE_APPLICATION_OVERLAY` window and places it with `SurfacePlacement` (uniform
scale, offset, crop to the decoder bounds from `VideoSurface`), plus a
SurfaceFlinger corner radius scaled into layer space. The display's root sits
just below the overlay window's own transparent buffer, so `FloatingChrome`,
the overlay's view, draws the PiP controls over it. `FloatingOutline` adds a
color layer for the hairline edge in the same transactions, so it never lags a
drag. `OverlayTouchRegion` limits the overlay's touchable area to the floating
rectangle through Android 10's internal-insets listener, so Maps keeps every
other touch; the video's input portal stays empty until fullscreen.

`EmbeddedDisplayLifecycle` (formerly `MapDisplayLifecycle`) now covers both
displays. Maps keeps its display awake and hidden while Wend is stopped.
The video keeps the stock power-off, except while presented elsewhere: host
surface changes then neither power it off nor reparent it, and the missed state is
applied when the presentation returns to Wend.

Maps signals `MapPanel.Covering` once, as it starts covering Wend. On a
slow expansion (Maps' buffers miss the 900 ms readiness deadline) the frozen
cover is still up, so the cover blanks the card the video left; otherwise the
cover would show a second, frozen copy. That path signals after the blocking task
move so the float's first frames are not starved, and never after a move that
failed. The live expansion signals as it begins, before the move; if the move
then fails, `Covering.abandoned` returns the video to its card
(`EmbeddedMaps.CoverOnce` keeps that order).

Once the video is fullscreen and receives touches, Android 10 sends Home to the
video's virtual display. `LiveSurfaceWindow` therefore routes the Home key to
Wend while a display is shown whole and,
after a dismissal, until Wend is shown again. Found on the emulator: without
this, Home did nothing after the title was cleared from fullscreen.

## Live Maps floats above fullscreen video

When the live video opens fullscreen (the "Open" action under its card), Maps
keeps running live in a small window above it. The `layout` preference
`floatingMaps` (default true) turns it off; `floatingMapsCorner` remembers its
corner, default bottom right (the passenger side).

- **Shape.** A 4:5 portrait of the map alone, at the floating video's area (288 ×
  162 dp): 193 × 241 dp. Feedback was that the whole panel, which floated before,
  was an odd shape and showed all of Maps' controls, unlike Android's vertical
  navigation PiP. Maps draws its own PiP layout only in system PiP, which Wend
  turns off (see above), so `FloatingMapCrop` crops the panel display instead:
  100 dp below its top, 80 dp above its bottom, centered. Measured on real Maps
  in guidance in the 70% panel, that leaves out the next-turn card (top 95 dp),
  the trip sheet (bottom 75 dp), the speed box and the compass, search and sound
  buttons, and keeps the centered car and the road ahead; crops of guidance
  screenshots were compared to choose it. The display is not resized and Maps
  keeps its panel layout, so outside guidance (search, route preview) the crop
  shows whatever Maps draws there, sheets included. Its card, the panel, still
  shows the whole display, and the window moves between the two.
- **Enter.** Maps lifts out of its panel into its own overlay without moving,
  the video expands from its card beneath it, then Maps shrinks into its corner
  (280 ms each). Moving both at once uncovers parts of the empty panel.
- **Drag, snap, outline, corners, catch mid-snap, tap grace after entering, PiP
  controls:** as for the floating video, without a playback control. Closed, Maps
  returns to its panel (beneath the fullscreen video) and keeps navigating.
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
  to the front (Wend stops) or the video presentation ends.
- **Maps closed while floating** (its task removed): the panel's "Maps was
  closed" message and its Open Maps button stay after Home returns Maps' empty
  display to the panel. The same message appears when Maps is gone as a tapped
  floating video becomes fullscreen, instead of Maps floating. Wend does
  not relaunch Maps on its own in either case.

## Live Maps floats above the video app Wend opens

When Wend opens the video app itself on the main display (a paused,
stopped, or unavailable preview; the dock or drawer icon without a live video;
or the launch-intent fallback when no video task exists), Maps floats above it
the same way, with the same window, `floatingMapsCorner`, and `floatingMaps`
switch. Before November 8 navigation disappeared whenever the video was paused
(device feedback from both reports).

- **Enter.** Maps moves from its panel to its corner (280 ms) as the video app
  opens (`MainActivity.videoAppOpening`, then `EmbeddedMaps.floatAboveVideoApp`).
- **While Wend is stopped.** Maps' display stays powered and hidden behind
  its window (`EmbeddedDisplayLifecycle`, presented elsewhere), its input portal
  is empty, and the overlay takes only the floating rectangle; the video app
  keeps every other touch.
- **Leaves** with a fade when another app's task is created or comes to the
  front, including Recents (`TaskFrontWatcher`, as for the floating video); the
  video app and Maps themselves do not count. If app switches cannot be watched,
  Maps does not float, so it can never stay above an unrelated app.
- **Home**, or Wend showing again because the video app left or failed to
  open, returns Maps to its panel once Wend's window can show it.
- **Double tap** brings Maps fullscreen as for any floating Maps. The video is a task
  on the main display, not a Wend presentation, so it does not float; it
  stays beneath, and Home embeds it in the card again if it plays. Maps stays in
  its floating window, under the frozen frame, until it moves: a display shown
  in no window is missing from SurfaceFlinger's layer dump, so `MapFrame` could
  not see it lay out. Its display and its view both take the full screen size
  (`EmbeddedDisplayLifecycle.resize`), because Wend may be stopped and lay
  out nothing, or still have its window. Without these, Maps moved at its panel
  size, the frozen frame held about 1.6 s and then showed panel-sized Maps for
  0.8 s before its relayout.
- **Wend's own state does not matter during that tap.** Android 10 can
  leave Wend resumed under the video app (when the video moved there from
  Wend's own display) and stop it only as the tap's frozen frame goes up,
  or stop it whenever the video app idles. Before November 8 that stop called
  the takeover off: Maps was left on Wend's unshown panel display with no
  window, so navigation disappeared (fixture report BUG-1, reproduced again on
  the old build), or, with a stopped Wend, floating Maps stayed whole,
  centered, and untouchable (review R1). Wend's stop is now expected for
  the whole takeover above the video app.
- **Called off** only by Home, by Wend showing again (the video app left),
  or by another app coming to the front during the takeover (review R2). Each
  removes the frozen frame at once, never moves Maps, and returns Maps to its
  panel: in view after Home, or hidden behind the other app.

## PiP controls

Both floating windows take Android 10's PiP gestures (`PipTouchHandler`,
`PipMenuActivity` in android-10.0.0_r47), drawn by Wend over its own
windows, not by SystemUI. They answer this feedback: no double tap to
fullscreen, no pause control, and a video window that vanished when its video
ended.

- **Tap** shows the controls over the window: a 30% black scrim, close in the
  top right corner, fullscreen in the middle and, for the video, play/pause below
  it (48 dp targets). The icon follows the video's session: pause while it plays
  or buffers, play otherwise. They fade after 3 s, a press on play/pause
  restarts that, and a tap on the scrim hides them. Like Android, a tap waits
  out the double-tap timeout (300 ms) before showing them, so a double tap never
  flashes them.
- **Double tap**, or the fullscreen control, swaps (see [swapping](#swapping)).
- **Play/pause** sends pause or play to the video's `MediaController`.
- **Close**, or a drag or fling off the bottom edge, closes the window. The
  video is paused and returns to its card; Maps returns to its panel and keeps
  navigating. While a window is dragged, a dismiss target (a dark circle with a
  cross) shows at the bottom center. Dragged, a window may leave the screen only
  through the bottom edge; released at least 30% below it, or flung down (at
  least 500 dp/s, mostly downward) from or into the bottom row of corners, it
  slides down and fades out over 160 ms. Otherwise it snaps to a corner as
  before.
- **An ended video** (session `STOPPED`, `ERROR`, or `NONE`) keeps its controls up,
  offering play, until it plays again.

`FloatingDrag` classifies the touches (tap, double tap, control press, drag,
snap, dismissal), `FloatingMenu` lays out the controls and maps the session's
state, and `FloatingChrome` draws them. Unit tests cover each gesture, the
control layout, the session mapping, and the window's behavior for each
(`FloatingDragTest`, `FloatingPlacementTest`, `FloatingMenuTest`,
`LiveSurfaceWindowTest`).

What still differs from Android 10's PiP:

- These are Wend's windows, not system PiP: the apps are not told they are in
  PiP, so YouTube draws its full watch page (cropped to the video) and Maps its
  panel layout (cropped to the map) rather than their own PiP layouts. Maps'
  next-turn instruction is therefore not in the floating window.
- The windows exist only while Wend presents them: they float above fullscreen
  Maps, the fullscreen video, or the video app Wend opened, and close when any
  other app comes to the front. System PiP floats above every app.
- No settings (app info) control, no prev/next or app-supplied actions, no
  accessibility actions, and no pinch resize. Android 10 also enlarges a PiP
  while its menu shows; these keep their size. Positions are the four corners
  only, where Android 10 lets PiP rest along the left and right edges.
- The video's fullscreen is Wend's whole-screen presentation of the same
  display, not the YouTube task moved to the front; it is interactive, so
  another video can be chosen there, and Home returns it to its card.
- The double-tap swap of Maps pauses before it grows while Maps lays out at full
  size (see [swapping](#swapping)); Android's expand animates at once.

## Swapping

Double-tapping a floating window, or its fullscreen control, brings that app
fullscreen and floats the other one when its preference allows (the video also
only while playing). A single tap only shows its [controls](#pip-controls). Neither task is
recreated or duplicated, no Play command is sent, and the video's task, virtual
display, and decoder stay the same.

- **Floating video over fullscreen Maps (tap the video).** The video grows from
  its corner to fullscreen above Maps (the existing tap motion). Fully hidden
  under it, Wend comes forward first and, after its next frame, the Maps
  task returns from behind it to its panel's virtual display, the same order as
  Home. Once Maps has laid out at the panel's size (`MapFrame`, 500 ms at most;
  after that it emerges as it is and finishes its layout inside the window),
  it emerges at its corner: 90% to 100% size and 0 to 1 opacity over 220 ms
  with `window_ease_out`. About 0.8 s from tap to floating Maps on the fixture. With `floatingMaps` off,
  Maps just waits in its panel and Home shows it there. The video is now above
  Wend rather than Maps, so the status bar fullscreen Maps kept disappears.
- **Floating Maps over fullscreen video (double-tap Maps).** Maps holds still in
  its corner (a frame of its own layers drawn over the window) while, behind
  it, its display and view take the full screen size and Maps lays out there
  (900 ms at most, `MapFrame`). It then grows straight from its corner to the
  whole screen at pixel scale one, already in its fullscreen layout, as the held
  frame fades over the first 120 ms. Feedback was that it grew only to its
  panel's size, centered, and snapped to full screen a moment later: before
  this, the growth stopped at the centered panel display, which was laid out at
  full size only afterwards, under a frozen frame. Once it covers the screen, a
  frozen frame of exactly those pixels covers the task move (the video hides
  beneath it, and the existing task moves to display 0), then crossfades over
  160 ms once Maps' buffers show on display 0 (300 ms at most), and the video
  emerges at its corner. The frozen frame is read from Maps' own display layers
  (`ActivityViewSurface.still`, Android 10's `SurfaceControl.captureLayers`);
  if they cannot be read, the screenshot stands in. If another app stops Wend
  during this, the takeover ends at once. A paused video, or `floatingVideo`
  off, returns the video to its card instead. Without a frozen frame (animations
  off, or the screenshot failed), the video still emerges only once the task has
  moved.

Why the Maps direction differs: Maps' display is panel-sized, so fullscreen Maps
needs one new layout, which must happen out of sight. The video direction hides
it under the live video. In the Maps direction, Maps is the one growing, and the
dock's live expansion cannot be reused as is: it reveals Wend around Maps,
whereas here the video's overlay must stay visible below Maps until Maps covers
the screen, and yet end above Maps. The swap therefore lays Maps out first,
held still in its corner, and grows the laid-out display. The cost is a pause
before the growth: about the length of Maps' layout (0.3 to 0.9 s with real
Maps in guidance on the loaded emulator, see below), during which the window
shows the frame it had at the tap.

Also found on the emulator: when Maps moved to its panel display while still
visible, the fixture's map SurfaceView sometimes kept its fullscreen-sized buffer
in the panel (seen twice in about 15 swaps, once inside floating Maps). Moving it from
behind Wend, as Home does, fixed it: 8 of 8 swaps then showed the panel-size
surface with no readiness timeout (`swap-surface/run-*.png`).

Found on the emulator: moving Maps to display 0 straight from its panel-sized
display left Wend resumed and visible behind fullscreen Maps (no pause or
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

### Maps readiness in live guidance (device report RH-3)

The report saw every swap wait out both 900 ms readiness deadlines with real
Maps in guidance. SurfaceFlinger layer dumps during guidance (panel and full
screen, sampled about every 45 ms on the device) showed the readiness rule
does match there, but late, and that it could also match too early:

- **Maps' own layout is slow on this host.** After its display changes size,
  real Maps in guidance took 0.3 to 0.9 s to draw its window and map surface at
  the new width (Maps' render thread logged frames of 0.7 to 3.7 s; host load
  average 20 to 43 on 32 cores). In route preview it is lighter.
- **Confirming it took three more dumps.** `MapFrame` wanted three matching
  queries in a row. Each is a SurfaceFlinger dump served on its main thread,
  50 to 130 ms here and up to a second during the display change, so the
  confirmation alone could exceed the deadline. Buffers at the new width are
  already on screen, so one matching query now reports Maps ready, and it logs
  how long that took.
- **A stale map surface.** Each time real Maps returns to its panel it keeps its
  previous map SurfaceView, still holding a full-screen buffer, beside the new
  one (three after about seven swaps). The old rule took any Maps SurfaceView at
  the target width, so in full screen it reported Maps ready while the live map
  was still panel-sized under the full-screen window (captured). It now uses the
  newest map surface inside a Maps window, with that window. Tests use the
  captured layers for guidance and for the fixture
  (`app/src/test/resources/dev/augie/wend/mapframe/`).

The hold limits: the swap to Maps waits up to 900 ms for the full-size layout
before the move. A 700 ms limit was tried and made swaps slower at this load:
Maps then moved before finishing and laid out a second time on the main display.
After the move it waits at most 300 ms instead of 900 ms, then shows Maps as it
is. Floating Maps waits at most 500 ms instead of 900 ms for its panel layout.

Measured on `road-home-google-api29`, real Maps in guidance and YouTube, three
swap pairs each, the old build (f5efa5a) and this one run back to back with the
same script (`build/evidence/rh3/ab-old/`, `ab-new2/`):

| | old, load 28 to 38 | new, load 30 to 34 |
| --- | --- | --- |
| Frozen frame on a swap to Maps (recording) | 0.91, ~1.0 to 1.3, 1.30 s | 0.88, 1.31, 1.39 s |
| Cover to move (log) | 0.79, 0.88, 0.93 s | 0.60, 0.97, 1.01 s (one at the 900 ms limit) |
| Move to Maps ready again (log) | 3 queries, not logged | 1 query, 8 to 65 ms |
| Maps returned beneath the video to floating (log) | 0.74, 0.95, 1.08 s (two at 900 ms) | 0.56, 0.57, 0.63 s (500 ms limit) |

On this host the frozen frame is dominated by Maps' own layout and Wend's
starved main thread, so it is not yet well under 0.5 s: on the map fixture the
same swap holds about 0.85 s, of which 0.44 s is the logged wait. Overlapping
Maps' layout with the growth motion is the next step: capture Wend's own
Maps display layer at the tap (`SurfaceControl.captureLayers`), grow that still
image, and resize the display at once. Not done here.

Device report BUG-1 (the dock expansion's "opening directly" fallback, then a
black panel and a stale frame on Home) started from this deadline. With one
query, three of three dock expansions under load 34 reached readiness (595,
700, 786 ms) and every Home returned Maps live (`build/evidence/bug1/`); one of
seven in the final matrices still fell back under load 43. The Home visuals
after such a fallback were not reproduced and are unchanged.

### How it works

`LiveSurfaceWindow` now presents any owned ActivityView display: the video and
Maps each have one. It adds the held entry, the emerging entry, `focus` (grow to
the whole display without input), `hideForCorner`/`emerge` (a fullscreen video
becomes a floating one while covered), and `returnOverCard`. `MapPanel.Takeover`
names the three moments of the Maps direction (covered, revealed, failed), and
`MapTransition.holdThenCrossfade` holds its frame, a `MapTransition.Still` of
Maps' own layers, until the task has moved. `EmbeddedMaps.ScreenTakeover`
remembers whether the tapped Maps floated above the live video or above the
video app, which decides what Wend's stop means: above the live video it
means another app came forward and ends the takeover; above the video app it is
expected. `MainActivity` routes both directions and Home.

## The video card

The card under the media title shows the selected video: live while it plays
there, and otherwise a cover. Before November 8 a tap on the live card reached
YouTube's own tiny player controls, and a paused, stopped, or returning video
left a black box (device reports RH-4, RH-5, BUG-3, and design feedback).

- **Tap.** The whole card is one target that opens the video exactly as its
  Open action does: a playing video grows to fullscreen with Maps floating, any
  other opens the video app on the main display with Maps floating above it.
  None of the card's touches reach YouTube any more. Fullscreen input is
  unchanged: the whole display receives touches once the video covers the
  screen.
- **Cover.** Whenever the live video is not shown in the card, the card shows,
  in this order: a recent still of the live video, the session's artwork, or a
  placeholder (Wend's panel color and the video app's 24 dp icon at the top
  left). A paused or stopped video adds a 48 dp play control when its session
  can play (its `PlaybackState` actions include `ACTION_PLAY` or
  `ACTION_PLAY_PAUSE`); a playing video on its way back shows none. YouTube
  stops and deactivates its session about 1.3 s after it leaves the screen
  paused, offering only `ACTION_PLAY_FROM_URI`: the panel's play button is then
  disabled, so before November 8 the card's control promised a play that did
  nothing (real-app report BUG-5). Such a card shows its picture alone, and a
  tap on it still opens the video. YouTube 21.43 supplies no artwork, so the
  still is what the driver sees.
- **When the cover shows.** As playback pauses or stops in the card, while
  Wend is stopped and as it re-acquires the video on Home (BUG-3), while the
  video floats or plays fullscreen, and while it travels back into the card. It
  is removed in the frame the live video shows again: when the surface is
  revealed after its geometry check, or at once when a floating or fullscreen
  video lands back in the card live. The live surface is hidden only after the
  cover has been drawn over it, so the card never shows black in between.

### How it works

`ActivityViewSurface.PreviewInput` says where touches on a display's preview
go. Maps' panel is unscaled and keeps `FORWARDED`. The video card is `KEPT`: its
input portal is empty while it is a preview. Forwarding never worked there:
Android 10 scales a portal's touch region and then offsets it by the already
scaled frame, so with the card at `[109,93][429,273]` the portal took
`[168,93][488,273]` (`card/before/02-dumpsys-input-live.txt`). The card's left
59 px took nothing, and a 41 px strip of the Maps panel beside the card went to
YouTube, whose window only covers the card. `EmbeddedVideo`'s tap target is a
visible view above the player, so ActivityView's tap-exclude region (the area of
Wend's window that lets touches through to the display, computed by
subtracting the views drawn above it) no longer contains the card, and Wend
receives those touches itself.

`VideoStills` takes the stills. While the card plays live and nothing of
Wend's covers it, it captures the card's own screen area once a second at the
card's size, with the same Android 10 `SurfaceControl.screenshot` overload as
the drawer and the map transitions, which leaves out secure layers. Stills stay
in memory and are never stored or sent. A capture is kept only after the card
has kept playing live for 500 ms more without interruption. That rule comes from
the recording `card/before/pause-fullscreen.mp4` (frames in
`pause-frames-8.3s.png`): YouTube's last moving frame was at 8.597 s, its pause
overlay (dimmed video, play control, time) appeared at 8.758 s and stayed, and
the session reported the pause 65 ms after the media key. A capture at the
moment playback stops would race that overlay under load; a confirmed capture
never contains it. The kept still is therefore 0.5 to 2 s older than the pause.

Wend's own drawing over the card is excluded: `MainActivity` marks the card
covered as a sheet opens and as Maps starts going fullscreen (its live expansion
sweeps over the card before Wend stops), and shown again when its window
regains focus or Wend restarts. A pressed card, a card with its cover up,
and any presentation elsewhere also take no captures.

`VideoCardCover` draws the cover and owns the stills. Memory is bounded to at
most three card-sized bitmaps (about 230 KB each at 320 x 180): the confirmed
still, the one last drawn, and one capture waiting for its check. The screenshot's
graphics buffer is copied once and freed at once. A replaced still that was
never drawn is recycled at once; one that was drawn is left to the collector,
because the renderer may still hold it. A new title or an ended session drops
the still, so a still of another video never stands for this one.

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
  Wend virtual displays stayed constant; no floating layers or overlay
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
  video task 95 (instance `716c39772b0`), Home task 94, the same two Wend
  virtual displays, the same process IDs including system_server, and no overlay
  windows or live, outline, or transition layers left.

### Limits

- The host was saturated during testing (load average above 30 on 32 cores,
  including the other emulator), so recordings drop frames and the main thread
  stalls, most visibly at the start of the video's expansion (a 70 to 117 ms gap
  in the animator's frame log, with or without floating Maps). Motion was judged
  from repeated recordings plus the animator's frame log. Motion still runs on
  the main thread (`ValueAnimator`); a dedicated animation thread would make it
  immune to binder calls and Wend's own drawing on the head unit.
- The Maps swap holds a frozen frame while Maps lays out at the full size and
  moves: about 0.85 s on the fixture and 0.9 to 1.4 s with real Maps in
  guidance on this loaded host (see [readiness](#maps-readiness-in-live-guidance-device-report-rh-3)),
  at most 900 ms before the move and 300 ms after it, plus main-thread delays.
- The stale map surface above is fixed for the swap by its order, not by a
  root cause in Android. Separately, early visits of the map fixture to display
  0 left extra fullscreen-sized SurfaceView layers under its window (seen with
  the dock path too; none appeared in the later runs). Both look like Android 10
  SurfaceView behavior across displays, possibly the stale-sized Maps frames
  reported in `task-fixture/GOOGLE-APPS.md`. Real Maps does keep one stale
  full-screen map surface per return to the panel; readiness now ignores them.
- With the video's live dock expansion, Maps visibly re-lays out inside its panel
  at the reveal before widening; that is the existing expansion, not the swap.
- Software rendering stalls the main thread as Wend stops, so recordings show
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
- The video card's still is 0.5 to 2 s older than the moment playback left the
  card, so a cut in the video during that time shows as a step back. Stills are
  taken only in the card: after a long floating or fullscreen presentation, the
  still is the frame from before the video left the card, which a pause while
  floating then shows on Home. Capturing the floating window as well would keep
  it current; not done.

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
  190, YouTube task 265, and Wend task 264 stayed the same throughout, no
  pinned stack appeared, and no floating window remained after either Home.
- Not reproduced on a device: Home cancelling a covered move (needs the live map
  motion to be unavailable), a failed task move, Maps closing while it floats,
  and another app coming forward while Maps prepares (the device tool's app
  launch arrived 1.5 s after the expansion had finished, and the existing
  dismissal closed the video, `f3b/`). These rest on the unit tests.
- Installing a new launcher APK while YouTube played in the card ended YouTube's
  embedded activity (task 253), as the README warns; YouTube was reopened from
  the dock. Move YouTube to fullscreen before replacing the APK.

### Readiness and Maps above the video app, November 8 (second pass)

Release and emulator unit tests (192 each, no failures), release, emulator and
windowed assembly, and release lint (no errors, 52 warnings) pass
(`build/evidence/gate-bb43c2a.log`). New tests cover the readiness rule on
captured real-Maps guidance and fixture layers, including the stale full-screen
map surface; ready on the first matching query; the 300 ms limit after a held
move; blanking everything but Maps in the frozen frame; floating Maps within
500 ms of returning beneath the video; and Maps floating above the video app:
it stays while Wend stops, another app ends it, Wend showing again
returns it, and its display grows directly only while Wend is hidden.

On `road-home-google-api29` (real Maps in guidance, YouTube), release build;
every APK was installed with YouTube moved to the main display first, so its
task (265) survived each install:

- Paused card, Open YouTube: before, YouTube fullscreen with no navigation
  (`item2-before/07-*`); now Maps floats in its remembered corner above paused,
  stopped, and playing YouTube from the card and the dock (`item2/01-*`, `03a-*`,
  `05b-*`). Maps' display stayed ON, its portal touch region was empty, and the
  overlay took only `[24,24][372,325]` (`item2/01-dumpsys-input.txt`,
  `01-displays.txt`).
- Home from it returned Maps to its panel with no floating window or layer left
  (`item2/02-*`); Home with YouTube playing embedded it live in the card
  (`04b-*`). Settings (`07-*`) and Recents (`09-*`) coming forward closed the
  float ("came to the front"); Maps' display stayed ON.
- Tap on floating Maps above YouTube: Maps fullscreen over it, laid out before
  the reveal, and Home returned it live (`item2/06-*`). Back in YouTube on the
  main display did nothing, like Maps' RH-1 below, so the video app leaving on
  its own was checked only through Home, Recents, and the unit tests. The
  unavailable preview and the launch-intent fallback take the same path in
  `AppLaunch` but were not opened on a device.
- Swaps and timings: `rh3/` (layer dumps, `ab-old/`, `ab-new2/`, `after1/`),
  dock-then-Home for BUG-1 (`bug1/`), and the full matrix of dock Maps, three
  swap pairs, Home, Open YouTube live, Home, paused Open YouTube with Maps
  floating, tap, and Home (`matrix-cf69d90/`, the build before the final one,
  which differs only in its 700 ms limit; `state.txt` shows Wend 269,
  Maps 190, and YouTube 265 throughout, no pinned stack, no floating window or
  layer after each Home, and Maps' PiP app-op still `ignore`).

On `road-home-api29` (fixtures), emulator build: the same direct open and tap,
dock Maps, both swaps, and Home (`fixture-cf69d90/`, `fixture-bb43c2a/`); map
fixture task 101, instance `900e3e41898`, and its route marker were kept, and
the video fixture task 107 survived three installs.

### The video card, November 8 (third pass)

Release and emulator unit tests (205 each, no failures), release and emulator
assembly, and release lint (no errors, 52 warnings) pass. New tests, each seen
failing first: a tap anywhere on the card opens the video, paused or playing
(`MediaPanelTest`); the scaled video preview forwards no touches while Maps'
panel does, and fullscreen input is unchanged (`ActivityViewSurfaceTest`); a
paused card without artwork is a play control on a subtle surface, not black,
at the panel and in `VideoCardCoverTest`; a still wins over artwork, and stills
are recycled when replaced unless they were drawn; a frame is kept only after a
later check finds the card still live, a frame taken as playback left is dropped
and freed, and nothing is captured while the card is not live or without the
platform signature (`VideoStillsTest`); the surface knows when it shows in its
preview again. Releasing a card whose player display was never created no longer
throws (found by these tests).

On `road-home-google-api29` with real Maps in guidance and YouTube, release
build of this code (evidence under this worktree's git-ignored
`build/evidence/card/`):

- Before (`before/`): the paused card was black with a small glyph
  (`01-home-paused-card.png`), a tap at the card's left edge did nothing and one
  in its middle showed YouTube's inline controls (`taps-before.png`), and the
  shifted portal region above.
- RH-5 (`rh5/`): with the video live in the card, the video portal's touch
  region is empty and Wend's window owns the card area
  (`03-dumpsys-input-live.txt`); a tap at the card's top left corner (130, 110)
  opened the video fullscreen with Maps floating, as the Open action does
  (`04-card-tap.mp4`, `04-after-card-tap.png`).
- First acquisition after Home: the placeholder with the YouTube icon, then live
  (`after/01-first-acquisition-sheet.png`).
- Pause in the card (`media dispatch pause`): live frames, then the cover with
  the still and its play control, with no black frame between them
  (`after/02-pause-frames-55-72.png`, `02-paused-card.png`).
- Home with the video floating over Maps: the video travels back over the still,
  Maps' existing settle cover then holds a frame showing the still, and the card
  is live afterwards (`after/03-landing-card.png`, `03-home-from-floating.mp4`).
- Home with "Video over fullscreen Maps" off (BUG-3): YouTube paused while its
  display was off, and the card showed the still with the play control from
  the first frame after Home until YouTube played again, never black
  (`after/05-home-nofloat-card.png`). At load average 35, YouTube took about
  11 s to resume on its own; Wend embeds only once it plays.

Not verified on a device: stop in the card, Home from the tapped fullscreen
video, the fixture emulator, and the closing floating-window matrix. The device
tool's scroll failed twice with `injectInputEvent returned false` (also with
`--debug`), so device work stopped there, with "Video over fullscreen Maps"
still off on that emulator (`after/07-device-state-at-stop.txt`).

### The takeover above the video app, the frozen frame, and the card's control, November 8 (fourth pass)

Release and emulator unit tests (233 each, no failures), release, emulator and
windowed assembly, and release lint (no errors; 53 warnings, one more
`PrivateApi` warning than before, for the `GraphicBuffer` class the layer
capture reads) pass for each of the four commits. Each new test was seen
failing first against the code before its fix:

- `EmbeddedMapsTest`, a floating window that really reaches its corner and
  grows whole (Robolectric windows given a compositor surface): a tap above the
  video app with Wend's window still there and Wend stopping
  mid-takeover; Wend never stopping; Wend stopping mid-takeover, and
  between the growth and the frozen frame's first frame; another app coming
  forward mid-takeover and before the frozen frame starts; Wend showing
  again; Home; and another app stopping Wend during a tap above the live
  video. Each ends with Maps moved once, nothing floating and no frozen frame,
  or with Maps unmoved in its panel at the panel's size and no frozen frame.
- `MapTransitionTest`: with the screen one frame short of the growth's end, the
  frozen frame still has Maps' pixels at its exact edges and black beside them;
  without Maps' layers it falls back to the screen, black outside Maps.
- `MediaPanelTest`: a stopped session offering only `ACTION_PLAY_FROM_URI` shows
  no play control and a tap still opens the video; the control returns when the
  session can play. The empty panel's subtitle is measured unclipped at 357 px
  ("Choose something in your music app" measured 4 characters cut; it is now
  "Choose something to play"). The paused sessions these tests use now offer
  play, as the fixture's (actions 518) and YouTube's do.

On `road-home-api29` (fixtures, emulator build; evidence under this worktree's
git-ignored `build/evidence/`):

- Before (`bug1-before/`): the original repro (playing card, pause, tap the
  card, tap floating Maps after the grace) lost Maps on the first try: Wend
  was resumed under the video app, Maps' display grew to 1280 px through
  Wend's layout, Wend stopped 140 ms later, the display shrank back, the
  frozen frame timed out, and the Maps task stayed on Wend's display with
  no window (`r1-repro.mp4`, `r1-after-tap-maps-lost.png`,
  `r1-stacks-after.txt`).
- After (`bug1-after/`, `final-fixture/`): eight runs. In seven, the same
  repro, Wend was resumed at the tap (once after an 11 s wait) and stopped
  30 to 250 ms after Maps' display grew, the moment that lost Maps before; in
  one (`run6-stopped`) Wend stopped as the video app opened, before the
  tap. Every run moved task 101 to the main display, fullscreen, with Maps
  reported laid out at 1280 px first and no overlay window left
  (`logcat-5556.txt`, `roadhome-log-runs.txt`). Runs 1 to 6 used the first
  commit's build, run 7 the second's, and run 8 the final build.
- BUG-2 (`bug2-after/`): two swaps to Maps from the fullscreen video, 20 frozen
  frames. None had a saturated colour of the video's test pattern in the 24 px
  beside Maps' left edge, or the video's dark background in the 14 px under its
  top edge; every frozen frame of the report's A5 recording has up to 540 and
  1,132 such sampled pixels there. Crops of both edges show Maps flush against
  black (`edge-before-after.png`, `swap2-edge.png`). The log has no fallback
  warning, so Maps' layers were read every time.

On `road-home-google-api29` (real Maps in guidance and YouTube, release build;
`real-before/`, `real-after/`):

- The previous build did not lose Maps in the one run tried here: Android
  stopped Wend 5 s after YouTube opened, before the tap
  (`real-before/r1-*`).
- Final build: three runs of paused card, tap the card, tap floating Maps. In
  two, Wend was resumed under YouTube at the tap and stopped 80 and 110 ms
  after Maps' display grew, the case that lost Maps on the fixture; in the third
  (from the stopped card) it stopped 1.2 s after YouTube opened. Each moved Maps
  task 190 to the main display in guidance (`item1-run*`,
  `logcat-5554-before-and-after.txt`). The frozen frame showed Maps' panel alone
  on black (`item1-run3-frozen-frame.png`).
- BUG-5: after Home, YouTube's session was `STATE_STOPPED` with actions 8192,
  and the card showed its still without a play control
  (`bug5-card-7s-after-home.png`); a tap on it opened YouTube with Maps floating
  (`item1-run2-card-opened-youtube.png`). The fixture's paused session (actions
  518) keeps the control (`final-fixture/00-home-paused-card-playable.png`).
- The empty panel after the APK replacement showed "Choose something to play"
  in full at the 70% layout (`bug5-home-card.png`).
- Regression (`regression/`): dock Maps with the video floating, the swap to
  the video, the swap to Maps, Home, a tap on the playing card, and Home. Maps
  task 190 and YouTube task 274 survived every step and the APK replacement,
  no floating window or frozen frame remained, YouTube kept playing in its
  card, and Maps' PiP app-op stayed `ignore`.

Not exercised on a device: another app coming forward during a takeover, and
Home during one (the frozen frame swallows touches and the device tool cannot
time a key into the second it lasts), and the screenshot fallback of the
frozen frame. These rest on the unit tests.

### PiP controls, the Maps crop, and the straight growth, November 8 (fifth pass)

Release and emulator unit tests (264 each, no failures), release and emulator
assembly, and release lint (no errors; 54 warnings, one more
`ClickableViewAccessibility` now that the overlay is a custom view) pass. The
gesture, placement, and controls tests were written first and failed against
the previous code; the rest were written with their change. New tests cover the gestures (`FloatingDragTest`, including
taps, double taps, control presses, drags, a drag after a tap, drags and flings
off the bottom, and a cancelled drag off the bottom), the bottom-edge geometry
(`FloatingPlacementTest`), the controls' layout and the session mapping
(`FloatingMenuTest`), the window's controls, their fade, close, playback, an
ended video, the separate floating region and the straight growth
(`LiveSurfaceWindowTest`), the crop (`FloatingMapCropTest`), and Maps laid out at
full size before it grows (`EmbeddedMapsTest`; the takeover's interruption tests
now interrupt while Maps lays out behind its held window, which is where a
takeover now waits).

On `road-home-api29` (fixtures, emulator build of the gesture commit; evidence
under this worktree's git-ignored `build/evidence/variant-b/`):

- The floating video without its shadow (`01-*`).
- A tap shows the scrim with fullscreen and close (`02-*`), a double tap swaps
  to the video with Maps floating and never flashes the controls (`03-*`),
  Maps' close control returns it to its panel, shown on Home (`04-*`, `05-*`),
  and a drag off the bottom shows the dismiss target, slides the video out, and
  leaves its session paused (`06-*`, `state=2` in `media_session`).
- The fixture's media stop was ignored (`08-*`): its session did not offer
  `ACTION_STOP`, so Android 10 dropped the key. The fixture now offers it and
  reports `STATE_STOPPED`, as YouTube does at a video's end.

Not verified on a device: the play/pause control, a stopped video staying
afloat with its play control, the portrait Maps crop, and the straight growth
of double-tapped Maps. The device tool's daemon stopped answering (every
request timed out at 90 s for over half an hour, while `adb` still answered)
right after the video fixture was reinstalled, so these rest on the unit tests.
The crop was chosen from crops of the real-Maps guidance screenshots of the
first pass (`07-*`), not on the real-apps emulator. The hairline edge was kept:
on the fixture it is barely visible, and it is meant for a dark video over a
night map, which was not checked here.

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
- The dispatcher still lists Maps' window as focused on the empty Wend
  Maps display and on many earlier, removed virtual displays (2, 4, 8, ... 58).
  Those entries only produce the "Found window ... but it should belong to
  display 0" log lines; they do not drop the key.
- Both emulators enable per-display focus with the boot overlay
  `dev.augie.wend.emulator.bootfocus` (`task-fixture/README.md`; images
  prepared before the rename carry it as `dev.augie.roadhome.emulator.bootfocus`);
  the PX6 does not.

The likely cause, not confirmed in AOSP source here: with per-display focus,
the Wend Maps display keeps Maps as its focused window while Maps is in the
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
