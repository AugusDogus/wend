# pip-hook

An LSPosed module for the Android 10 head unit that makes Wend's floating Maps and video move
instead of cut. It runs in three processes.

## In Google Maps and YouTube (`PanelAppHook`, `DisplayMoveCache`)

- Wend sends `dev.augie.wend.action.ENTER_PIP_FROM_PANEL` as an ordered broadcast (senders need
  `MANAGE_ACTIVITY_STACKS`). The app enters its own PiP and the hook answers whether it did:
  - **Maps** through its own `onUserLeaveHint`, so it chooses its navigation PiP itself.
  - **YouTube** directly, as a 16:9 player: YouTube declines from `onUserLeaveHint` but renders its
    player PiP once in it. Wend passes where the card shows on screen (`dev.augie.wend.START`), since
    the card scales YouTube's display down.
- After the app enters or leaves PiP, or moves between displays outside PiP, the hook reports once
  its new layout is drawn: its set of shown views has changed (or 250 ms passed) and that frame is
  committed. The report is a marker `setPictureInPictureParams` call (`PipSignals`).
- While an app handles a configuration change, each display's state is read once. Android 10 reads
  it over binder for every view when an app moves between displays; for Maps that was 330 to 880
  calls per move and held its main thread for 160 to 880 ms as it entered its PiP.

## In system_server (AOSP `android-10.0.0_r47`)

- **Entering PiP from a panel** (`PanelPipHook`). The app moves to the main display without being
  drawn fullscreen, and Android's own shrink starts from the panel's screen bounds. For a scaled
  panel (the video card), a still of the card covers the app's first frames, moving with the shrink.
  As the shrink lands, a still covers the app while Android resizes it, until the app reports its
  PiP layout (2.5 s cap), then crossfades.
- **Growing out of a card or icon** (`CardGrow`). A task Wend moves with `dev.augie.wend.GROW_FROM`
  grows from those bounds to fullscreen, on the shrink's curve and duration, over a still of Wend.
- **Expanding out of a panel** (`PanelExpand`). A task Wend moves with `dev.augie.wend.EXPAND_FROM`
  (Maps, as the video floats) stays hidden while a still of its panel grows to fullscreen, until the
  app reports its new layout.
- **Home** (`CardReturn`, `PanelReturn`). A video Wend grew out of a card shrinks back into it, and a
  PiP Wend moves back into a panel grows into a picture of that panel taken as it left, which covers
  the app until it reports its new layout. Both wait for Wend's window, so they start with it.
- **PiP edges** (`PipEdges`). The PiP's app is cropped to the PiP's bounds and the module draws the
  PiP's shadow itself. SurfaceFlinger keeps showing video layers YouTube has removed (one each time
  it moves between displays), and one showed as a blurred border in the margin Android leaves around
  the PiP for the app's own shadow.
- The module's own animations run on their own display-priority thread (`MotionThread`), not on
  Android's `android.anim`, which blocks on the window manager's lock while Wend resumes.

Without the module nothing answers Wend's broadcast; Wend floats Maps by the task-switch path
(`MainActivity.floatMapsByTaskSwitch`), the video by its own task switch, and moves cut as before.

## Build

```
./gradlew :pip-hook:assembleDebug
```

## Install (rooted with Magisk and Zygisk)

1. Install LSPosed (Zygisk build, tested with v1.9.2) and reboot.
2. `adb install -r pip-hook/build/outputs/apk/debug/pip-hook-debug.apk`
3. Enable the module for **System Framework**, **Google Maps**, and **YouTube** in LSPosed. Without
   the manager app, the same rows can be written to `/data/adb/lspd/config/modules_config.db` as
   root: set `modules.enabled = 1` for `dev.augie.wend.piphook`, and add `scope` rows `system`,
   `com.google.android.apps.maps`, and `com.google.android.youtube` (user 0) for its `mid`.
4. Remove Maps and YouTube from Magisk's denylist (`magisk --denylist rm <package>`); an enforced
   denylist unloads Zygisk, and so the hook, from them.
5. Reboot. `/data/adb/lspd/log/modules_*.log` then shows the system_server hooks (`system_server`,
   `card grow`, `panel return`, `card return`, `panel expand`) and `LEAVE_HINT` (Maps) and `PLAYER`
   (YouTube) as installed.

Every system_server change needs a reboot; a Maps or YouTube change needs that app restarted.
