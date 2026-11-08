# Launcher app catalog and drawer sheet

The user reported that the app list took seconds to load on every opening and could
not be dragged down to dismiss. The drawer now shows a launcher-wide cached catalog
immediately and is a draggable glass bottom sheet. Validated on the
`road-home-google-api29` emulator (Android 10, 1280 x 720, 240 dpi) with the
release build on November 7, 2026. Not yet installed on the PX6.

## App catalog

Before: every opening created a new dialog, waited for a screenshot and a fresh
RenderScript context, then queried PackageManager and loaded every label and icon,
and finally rebuilt every grid view on each keystroke.

`AppCatalog` follows Launcher3's android10-release `LauncherModel`, `AllAppsList`,
`IconCache` and `LauncherAppsCompatVL`:

- It loads once on a background thread when `MainActivity` starts, through
  `LauncherApps.getActivityList(null, user)` for each profile.
- `AppList` keeps one entry per package and profile: the package's first launcher
  activity, which is the one Android's launch intent opens. Road Home itself is
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
`addListener` / `removeListener`, and `retryIfFailed()`. Never recycle a
`LauncherApp.icon`.

Failures are logged with the package and the effect. A failed initial load shows
"Apps could not be loaded" and retries on the next opening. A failed package reload
keeps that package's previous entry. An icon that cannot load uses Android's
default activity icon.

## Drawer

`MainActivity` keeps one `AppDrawer`, built when the launcher first goes idle. Tiles
are single `TextView`s with a compound icon in `AppGrid`, which lays out fixed cells
and skips hidden tiles. Search only toggles tile visibility. Catalog changes reuse
existing tiles by package and profile. Dismissal clears the query and scrolls to the
top. Rows fade under the header and at the bottom edge. Tapping a tile removes the
sheet at once and opens the app through the existing Maps and YouTube task paths;
apps in another profile open through `LauncherApps.startMainActivity`.

## Bottom sheet

`BottomSheet` is a reusable glass sheet in its own window. The settings screen can
supply a different header and body. Related classes: `SheetPanel` (touch),
`SheetDrag` (release rules), `SheetGlass` (surface), `FrostedBackdrop` (capture).

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
- The close button, a tap on the backdrop, Back, and Home all use the same exit.
  Opening an app and `onStop` remove it immediately.
- With Android's animator duration scale at 0, every transition jumps to its end.
  Drags still follow the finger.

### Backdrop timing

The capture must not include the sheet, and must not hold it back. The window opens
immediately with the sheet below the screen and a transparent backdrop. The slide
starts as soon as the screen pixels are taken: at the window's first pre-draw if they
already are, otherwise on the capture callback, otherwise after 64 ms. A capture that
finishes after the slide began is discarded and the sheet keeps its denser plain
tint. Blurring continues off the main thread and fades in. One RenderScript context
per drawer is created when the launcher is idle and reused.

## Validation

Timings come from `RoadHome` log lines on the emulator, three or more openings each.

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
- The backdrop strip is 24 dp, below the 48 dp target. The 48 dp close button and
  Back remain the primary ways to close. Dragging is touch only; the handle is
  decorative to accessibility services.

Evidence (git-ignored): `build/evidence/app-catalog/` and `build/evidence/drawer-sheet/`.
