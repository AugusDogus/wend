# Launcher UI: app catalog, drawer, dock and settings

The user reported that the app list took seconds to load on every opening and could
not be dragged down to dismiss. The drawer now shows a launcher-wide cached catalog
immediately and is a draggable glass bottom sheet.

The user then reported that settings looked bad and could not add or remove dock
apps. The dock is now a configurable list of pins, and settings is a glass sheet with
live layout controls and a dock editor; see [Configurable dock](#configurable-dock)
and [Settings sheet](#settings-sheet).

None of this has run on the PX6. Validation happened per lane on Android 10
emulators (1280 x 720, 240 dpi); [Validation scope](#validation-scope) says what was
checked before the merge, on the merged build, and for the review fixes.

## App catalog

Before: every opening created a new dialog, waited for a screenshot and a fresh
RenderScript context, then queried PackageManager and loaded every label and icon,
and finally rebuilt every grid view on each keystroke.

`AppCatalog` follows Launcher3's android10-release `LauncherModel`, `AllAppsList`,
`IconCache` and `LauncherAppsCompatVL`:

- It loads once on a background thread when `MainActivity` starts, through
  `LauncherApps.getActivityList(null, user)` for each profile.
- `AppList` keeps one entry per package and profile: the package's first launcher
  activity, which is the one Android's launch intent opens. Wend itself is
  excluded. Apps sort by label with the current locale's `Collator`, then by package.
- A `LauncherApps.Callback` on the catalog's worker thread reloads only the affected
  package. Added, changed and available packages are re-queried; removed packages,
  and unavailable ones that are not being replaced, are dropped. A locale change
  reloads everything, as Launcher3 does.
- Labels and icons stay in memory. Icons are pre-rendered once into shared 48 dp
  antialiased squircle bitmaps (`AppIcon.render`), so no view loads a drawable.
- Listeners run on the main thread with an unmodifiable sorted list.

API for the configurable dock and the settings app picker: `AppCatalog.get(context)`
on the main thread, `status()` (`LOADING`, `READY`, `FAILED`), `apps()`,
`pinnedApp(packageName)`, `canPin(app)`, `addListener` / `removeListener`, and
`retryIfFailed()`. Pins name packages in Wend's own profile; `pinnedApp` is the
one place that rule is applied for the dock, the dock editor and the layout preview.
Never recycle a `LauncherApp.icon`.

Failures are logged with the package and the effect:

- An icon that cannot be loaded or drawn uses Android's default activity icon. An
  app whose label cannot be read is left out. Neither fails the rest of the list.
- A failed package reload keeps that package's previous entry.
- A failed initial load (LauncherApps unavailable or refusing the query) shows "Apps
  could not be loaded" in the drawer and the picker, and loads again the next time
  the drawer, Settings or Add app opens. Meanwhile `pinnedApp` reads each pin from
  PackageManager (its launch activity, label and icon) on the main thread, once per
  pin until the next load, so the dock keeps Maps and its other installed apps.

## Drawer

`MainActivity` keeps one `AppDrawer`, built when the launcher first goes idle. Its
grid and search are an `AppBrowser`, which the settings app picker reuses. Tiles
are single `TextView`s with a compound icon in `AppGrid`, which lays out fixed cells
and skips hidden tiles. Search only toggles tile visibility. Catalog changes reuse
existing tiles by package and profile. Dismissal clears the query and scrolls to the
top. Rows fade under the header and at the bottom edge. Tapping a tile removes the
sheet at once and opens the app through `AppLaunch`, which keeps the existing Maps
and YouTube task paths; apps in another profile open through
`LauncherApps.startMainActivity`. The search field is labelled by its hint alone, so
TalkBack reads the typed query, and the status line ("No apps match …") is a polite
live region, so TalkBack announces it.

The drawer has no close button: dragging down, Back, a backdrop tap and Home close
it. Its header is only the search field, centered under the handle with 56 dp on each
side (the room the settings header gives its close button and balancing space), so it
reads the same left to right and right to left.

## Bottom sheet

`BottomSheet` is a reusable glass sheet in its own window; the drawer and settings
each supply a header and body. Related classes: `SheetPanel` (touch),
`SheetDrag` (release rules), `SheetGlass` (surface), `FrostedBackdrop` (capture).
Both sheets share one `FrostedBackdrop` (one worker thread and RenderScript context),
owned and released by `MainActivity`. `SheetGlass.Density` is `LIGHT` for the drawer's
icon grid and `DENSE` for settings, which is mostly text (see below).

- Layout: full width, a 24 dp frosted gap above, 16 dp top corners, a 36 x 4 dp
  handle centered in a 20 dp zone, then the 48 dp header row.
- Glass: the blurred capture is drawn at display coordinates, so it stays aligned
  with the launcher beneath while the sheet moves. Behind and above the sheet the
  same capture is dimmed by 40% black. Dim and frost scale with how open the sheet is.
- Motion: opening slides up over 300 ms with Vaul's `cubic-bezier(0.32, 0.72, 0, 1)`.
  Closing uses the same curve over 200 ms, scaled by the remaining distance, with a
  120 ms minimum. Springing back takes 240 ms. The blur fades in over 150 ms.
- Dragging: a vertical drag in the header, or a downward drag in content already
  scrolled to its top, moves the sheet 1:1. Upward overdrag rubber-bands, starting
  at 0.55 of finger speed and never reaching 24 dp. The panel extends 32 dp below
  the screen so overdrag never shows its edge. Only the first finger counts.
  Grabbing the sheet while it opens or springs back takes over from its position.
- Release: a downward fling of at least 500 dp/s dismisses; an upward one of at
  least 500 dp/s keeps it open; otherwise it dismisses at 25% of its travel and
  springs back below that.
- Settings' close button, a tap on the backdrop, Back, and Home all use the same exit.
  Opening an app and `onStop` remove it immediately.
- With Android's animator duration scale at 0, every transition jumps to its end.
  Drags still follow the finger.

### Backdrop timing

The capture must not include the sheet, and must not hold it back. The window opens
immediately with the sheet below the screen and a transparent backdrop. The slide
starts as soon as the screen pixels are taken: at the window's first pre-draw if they
already are, otherwise on the capture callback, otherwise after 64 ms. A capture that
finishes after the slide began is discarded and the sheet keeps its denser plain
tint. Blurring continues off the main thread and fades in. The shared
`FrostedBackdrop` creates its RenderScript context on its worker thread as soon as
`MainActivity` creates it, and reuses it for every capture.

## Drawer validation (before the merge)

On `road-home-google-api29` with the release build of the drawer lane, November 7,
2026. Timings come from `Wend` log lines, three or more openings each.

| Build | First opening after launcher start | Later openings |
| --- | --- | --- |
| Before | 587 ms to drawn icons; "Loading apps…" on every opening | 85 and 111 ms |
| Cached catalog, reused drawer | 163 to 308 ms to drawn icons | 32 to 53 ms |
| Bottom sheet | Slide starts 152 ms after the tap | Slide starts 15, 27 and 30 ms after the tap |

The catalog loaded 15 to 27 apps in 73 to 211 ms in the background at launcher start.
The idle drawer build took 11 to 37 ms. A warm capture took 3 to 10 ms and blurring 2
to 14 ms. With the sheet, every opening shows the cached apps in its first visible frame.

Also verified on the emulator, with screenshots and recordings:

- Installing the Maps test fixture while the drawer was open added it in sorted
  position. Uninstalling it while "map" was searched removed it live. Twelve
  throwaway launcher apps were installed (27 apps) and removed (15 apps) the same way.
- Search filtered existing tiles and reset after closing. A tile launched its app.
- With 27 apps, scrolling worked, and a downward drag in a scrolled list scrolled it
  instead of moving the sheet.
- Releases logged: 11% at 67 dp/s and 14% at 73 dp/s sprang back; 36% at 133 dp/s,
  7% at 933 dp/s and 9% at 889 dp/s dismissed. Frames show the sheet following the
  finger 1:1 (98 px for 110 px of travel less the 12 px touch slop).
- Upward overdrag of about 108 px raised the handle 23 px (15 dp), then it sprang back.
- Close button, backdrop tap, Back and Home each played the same 120 to 160 ms exit.
- With animations turned off, the sheet appeared fully open in the next frame and
  vanished at once on dismissal. Animations were turned back on afterwards.

## Limits

- No PX6 validation. The emulator's frames take 20 to 30 ms (median) while the sheet
  moves. The sheet's own UI work is under 1 ms per frame; most of the time goes to
  the emulator's GPU and to the dock button's press ripple redrawing on the shared
  UI thread. This is not a 60 fps claim.
- The first opening after a launcher start is still 150 to 300 ms on the emulator. It
  is dominated by the first draw of a new window and the first hardware-bitmap
  readback on the RenderThread. Warming a generated hardware bitmap or a real capture
  did not remove it, so only the RenderScript context is prepared early.
- The emulator shows no soft keyboard, so resizing the sheet around the IME was not seen.
- Work-profile launching, locale-change reloads, and catching the sheet mid-animation
  were not exercised.
- The backdrop strip is 24 dp, below the 48 dp target. In the drawer, which has no
  close button, Back, Home and that strip (TalkBack offers it as "Close app drawer")
  are the only ways to close without dragging; settings keeps its 48 dp close button. Dragging
  is touch only; the handle is decorative to accessibility services.

Evidence (git-ignored): `build/evidence/app-catalog/` and `build/evidence/drawer-sheet/`.

## Configurable dock

The dock column is `Dock`: the clock and date, the pinned apps, then the fixed Apps
and Settings buttons. Pins are a `DockPins` value: an ordered list of distinct, valid
package names (two or more dot-separated segments, each starting with a letter).

- Default: `BuildConfig.MAPS_PACKAGE`, then `com.spotify.music`.
- Stored in SharedPreferences `layout`, key `dockPins`, as comma-separated packages.
  A missing key means the defaults; an empty string is a dock the user emptied.
  Invalid names, repeats and pins past the capacity are dropped and logged; a value
  of the wrong type is logged and reads as the defaults.
- Capacity: `DockLayout.pinCapacity` takes the display's shorter side in dp. The
  clock (42), date (22), bottom padding (4), Apps and Settings (52 each: a 48 dp target
  plus a 4 dp gap) leave 308 of 480 dp, so five 52 dp slots fit on the PX6.
- Icons come from `AppCatalog.pinnedApp`, so the dock follows installs, removals and
  pin changes live. A pin whose app is not installed (or not in the main profile) is
  skipped in the dock but stays stored, and comes back in place when the app is
  reinstalled. Until the catalog first loads, the dock reserves the pins' slots so the
  Apps button does not jump, then fades the icons in over 150 ms. If that load fails,
  the pins come from PackageManager instead (see [App catalog](#app-catalog)), and a
  retry keeps the buttons already shown until it finishes.
- Tapping a pin goes through `AppLaunch.open`, the routing that used to live in
  `AppDrawer`: Maps opens fullscreen through `MainActivity.openMapsFullscreen`, the
  video package tries the live-video path first, and an existing YouTube task is
  restored instead of relaunched.

## Settings sheet

`SettingsSheet` is a `BottomSheet` (it replaced an AlertDialog) with denser glass, a
header of [close] [Settings] [balancing space], and a centered column (at most
680 dp of the 853 dp screen) of grouped cards. Section labels are small and muted;
rows are at least 64 dp tall and every control is a 48 dp target. Each segment of a
`SegmentedControl` spans the control's full 48 dp height; its thumb and press ripple
are drawn 3 dp inside it. Builders for the rows live in `SettingsRows`. Layout is
start/end relative; directional glyphs (back, forward chevron) mirror in right-to-left
languages.

- Layout: Map position (Left / Right) and Map width (Balanced 60% / More map 70%)
  as `SegmentedControl`s. A raised thumb slides to the choice over 220 ms with a
  strong ease-out. Choices are `LauncherSettings.MapSide` and `MapWidth`, stored under
  the original keys `mapsOnRight` and `mapPercent`, so existing installs keep their
  layout. `LayoutPreview` draws the screen: the dock with its real pinned icons, the
  media panel and a night-style map. The map slides and resizes over 280 ms
  (ease-in-out) and the media panel is revealed around it rather than squeezed. Left
  and Right are physical places in the car, so the preview, the Left/Right control and
  the launcher's row of panels never mirror; each panel's own content still follows
  the locale.
- Applying a layout reattaches the Maps ActivityView. On the emulator that blocks the
  shared render thread for about 100 ms, which froze the first frames of the preview.
  The real panels are now rearranged once the preview settles (320 ms after the last
  tap, so quick taps coalesce) or as soon as the sheet starts closing, whichever comes
  first. Behind the sheet the launcher therefore changes within a third of a second.
  In the archived `windowed` build the panels are rearranged only once the sheet's
  window is gone: its Maps corner masks are overlay windows that a relayout re-adds
  above every older window, the open sheet included.
- Dock: the pins in dock order with icon, name, move up, move down and remove. The
  count ("2 of 5") sits beside the section label. Moved rows slide 240 ms; the row you
  moved rises above its neighbor (lighter fill, raised Z) and sinks back when it lands,
  so the two never pass through each other. Removed rows fade and shrink over 120 ms
  before the rows below close the gap, and the card resizes with them. A pin that is
  not installed shows its package name, a dashed placeholder and "Not installed";
  while the app list is still loading it says "Loading…" instead. A row pinned just
  before another edit is shown at full opacity rather than left mid-fade.
- Add app opens a picker page inside the sheet: the same `AppBrowser` grid, offering
  only apps in Wend's own profile and hiding apps already pinned, with search.
  Opening it retries a failed app list. Choosing an app pins it at the bottom and
  returns. Back, or the back arrow, returns without pinning. Empty searches say what
  happened: "No apps match “Waze”. Try another name.", "YouTube is already in the
  dock." (only for pinned apps; work-profile apps never count as matches) or "Every
  app is already in the dock." At capacity, Add app is dimmed and explains "The dock
  is full. Remove an app to add another."
- Music app: its own section below the Dock, a single card row showing the chosen music
  app's icon and label, or its package name and "Not installed" (like a dock pin) when the
  app is absent, and "Loading…" while the app list loads. The row opens the same `AppBrowser`
  picker page (grid and search), offering every app in Wend's own profile with nothing
  hidden; choosing one calls `LauncherSettings.setMusicApp` and returns to the sheet. The
  choice is stored in `layout` under the key `musicApp`, defaulting to
  `BuildConfig.MUSIC_PACKAGE` (`com.google.android.apps.youtube.music`); a wrongly-typed or
  non-package value is logged and reads as that default until an app is chosen. It is a
  dedicated section rather than part of Layout or the Dock because it configures the media
  sidebar, not the map geometry or the dock pins, and stays independent of the dock. The
  media panel reads it live through a `Supplier<String>`, so the empty-panel "Open <app>"
  fallback and the paused-session tie-break follow a new choice without rebuilding the panel;
  `MainActivity`'s settings host relabels the panel when it changes.
- Floating windows: "Video over fullscreen Maps" (`floatingVideo`) and "Maps over
  fullscreen video" (`floatingMaps`), both default on, stored in `layout` and read
  through `LauncherSettings.floatingVideo()` and `floatingMaps()`. `MainActivity`
  reads them each time a window would float; the windows are described in
  [floating windows](floating-windows.md).
- System: Android settings and Default home app open the existing intents through
  `AppLaunch.openSystemScreen`, which suspends the Maps and video overlays like any
  external app and restores them if Android cannot open the screen.
- Footer: "Wend" and the version name.
- Dragging, the close button, a backdrop tap, Back (after leaving the picker) and Home
  all dismiss it, as with the drawer. Dismissal returns to the main page, clears the
  picker search and scrolls to the top.

Press ripples were invisible on every transparent button in the launcher (dock icons,
close buttons, drawer tiles): Android masks a ripple by its content, and a transparent
background masks it completely. `Ui.ripple` now gives those ripples an explicit
rounded mask, so presses show.

### Settings contrast

Over Maps' bright day tiles the drawer's glass measured #4a5b60 and a card #666c72,
giving muted text about 2.6:1. Settings uses `SheetGlass.Density.DENSE` (88% tint
instead of 72%) and 8% white cards; the same area then measured #323d46 for the glass
and #444b52 for a card: about 5.5:1 for muted section labels on the glass and 4.4:1
for muted secondary text inside cards. Primary text is above 8:1 on both.

### Settings validation (before the merge)

Verified on `road-home-google-api29` with the release build of the settings lane,
before the floating-window lane was merged, with screenshots and recordings:

- Before: the AlertDialog (`settings/before-settings-dialog.png`).
- Every section on screen: layout, dock, floating windows, system and footer
  (`settings/final-top.png`, `settings/v2-middle.png`, `settings/v2-bottom.png`).
- Map side and width changed live; the preview morph and an interrupted change were
  inspected frame by frame (`settings/layout-live.mp4`, `layout-side-frames.png`,
  `layout-interrupt-frames.png`). With the deferred relayout, the preview window drew
  a frame on every vsync during the morph and the main window relaid out afterwards
  (`settings/gfxinfo-side-1014.txt`, `gfxinfo-side-802.txt`). UI-thread work was under
  3 ms for 31 of 33 frames (6.5 and 10.7 ms for the other two); emulator rendering
  took 12 to 58 ms per frame on a host with a load average of 14.
- Pins removed, added through the picker, reordered and filled to capacity, with the
  dock updated after each change (`dock/removed-spotify.png`, `picker.png`,
  `added-youtube.png`, `moved-youtube-up.png`, `full-dock-disabled-add.png`,
  `home-five-pins.png`, `home-youtube-maps.png`). Reorder, removal and the lifted row
  were inspected frame by frame (`dock/edit-pins.mp4`, `reorder-lifted.mp4`,
  `reorder-final.mp4` and their `*-frames.png` sheets).
- Installing the Maps fixture, pinning it, uninstalling it (settings showed "Not
  installed" and the dock skipped it) and reinstalling it (back in fifth place), with
  no reload (`dock/fixture-uninstalled-settings.png`, `fixture-dock-compare.png`).
- Picker search with no match, a pinned-only match and the already-pinned message
  (`dock/picker-no-match.png`, `picker-already-pinned.png`).
- Back from the picker, Back and Home on the main page, and a tap on the disabled Add
  app (`settings/back-home-sequence.png`). Drag down: a 19% pull at 133 dp/s sprang
  back and a 47% pull at 244 dp/s dismissed, revealing the updated dock
  (`settings/drag-dismiss.mp4`, `drag-dismiss-frames.png`).
- A floating-window switch turned off stayed off after closing and reopening
  (`settings/switch-maps-off-reopened.png`); it was turned back on. That lane had no
  floating windows, so what the switches control was checked only after the merge.
- Android settings and Default home app opened, and Home restored the launcher with
  Maps task 190 in its panel (`settings/system-rows-sequence.png`).
- The YouTube pin opened YouTube; a second tap reused task 253 instead of creating
  another (`dock/youtube-pin-opened.png`). The Maps pin moved task 190 fullscreen and
  Home returned it (`dock/maps-pin-opened-fullscreen.png`).
- With animations off, every change applied on the next frame
  (`settings/animations-off.mp4`, `animations-off-pair.png`). Animations were turned
  back on afterwards.
- Press ripples before and after the fix (`settings/ripple-contact.png`, then the
  close button in `picker-transition-frames.png`).

Unit and Robolectric tests: `DockPinsTest` (parse and serialize, defaults, empty
versus missing, garbage, dedupe, capacity, add, remove, move, immutability),
`DockLayoutTest` (capacity), `DockTest` (a full dock measured at 480 dp keeps every
button a 48 dp target and Settings on screen; pins from PackageManager after a failed
load, kept through a retry), `DockEditorTest` (loading versus not installed, an
interrupted fade-in), `AppCatalogTest` (an unreadable icon or label, own-profile pins,
the PackageManager fallback), `AppBrowserTest` (work-profile apps left out of the
picker, the pinned message, TalkBack reading the query, announced results),
`SettingsSheetTest` (when a layout change reaches the launcher, in each build; choosing a
music app through the reused picker stores it and relabels the media panel),
`MediaPanelTest` (the empty-panel fallback and the paused-session tie-break follow the chosen
music app), `LauncherSettingsTest` (defaults, existing keys, wrong types, unknown widths,
floating keys and corners, pins, the music app's default, round trip and bad value),
`LayoutPreviewTest` (map geometry and corner rounding) and
`SegmentedControlTest` (selection, listener, accessibility, a segment measured in its
settings row).

### Settings limits

- No PX6 validation, and the emulator's frame rate is not a hardware claim.
- The blurred backdrop is captured when the sheet opens, so the panels and dock under
  the glass do not visibly change until the sheet moves or closes. The preview is how
  layout and dock changes read while it is open.
- Right-to-left layout was not checked on a device: changing the locale or forcing
  RTL needs settings this session does not drive. Very long app names were not seen
  on screen; rows ellipsize at one line and picker tiles at two.
- A soft keyboard never appeared on the emulator, so the picker search was typed with
  injected text, not an IME.
- Pins are per package in the main profile; work-profile apps are not offered.
- The dock's reserved slots and 150 ms icon fade while the catalog first loads were not
  seen on screen; the settings sheet's backdrop tap was not tried (it is the drawer's
  verified `BottomSheet` path).
- Shrinking the stored capacity (a shorter screen) drops the surplus pins the next
  time the dock is saved.
- After a move, the moved row's arrows are no longer under the finger, so moving an
  app several places takes taps on different rows (or the neighbor's arrows).
- Emulator state left behind: release build installed, Maps then YouTube pinned, Maps
  on the right at 70%, both floating-window switches on, the Maps fixture uninstalled.

Evidence (git-ignored): `build/evidence/settings/` and `build/evidence/dock/`.

## Validation scope

- Drawer and settings lane (the validation sections above): `road-home-google-api29`
  with the release build, November 7, 2026, before the floating-window lane was
  merged. Evidence (git-ignored): `build/evidence/app-catalog/`, `drawer-sheet/`,
  `settings/` and `dock/`.
- Merged build (911dc7a), review device runs on November 7 and 8, 2026. On the
  fixture emulator (`road-home-api29`, emulator variant) the floating-window switches
  were toggled through this sheet across the whole floating matrix; both sheets opened
  over a playing video card without blanking it; a map side and width change while
  the video played kept both tasks and placed the floating windows for the new layout
  (the next dock expansion then hit the slow Maps fallback, tracked with the floating
  windows); the video fixture was pinned through the picker and opened from the dock;
  and Home closed an open drawer, settings page and picker. On
  `road-home-google-api29` with real Maps and YouTube (release build), the drawer
  started opening 25 to 76 ms after the tap with icons in its first frame, and every
  settings section, dock edits, a layout change during guidance and playback, both
  switches and both system links worked. Evidence (git-ignored, main checkout):
  `build/validation/merged-regression-api29/` and `build/validation/real-apps-api29/`.
- Review fixes described above (one unreadable app no longer fails the list, the
  PackageManager fallback for pins, the editor's loading label and fade-in, the
  picker's profile filter, search accessibility, full-height segments and the
  windowed relayout): unit and Robolectric tests in the release, emulator and windowed
  variants only. Not yet run on an emulator.
- PX6: none of the above.
