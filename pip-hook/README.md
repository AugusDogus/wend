# pip-hook

An LSPosed module for the Android 10 head unit that makes Wend's floating Maps move instead of cut.
It runs in two processes:

- **Google Maps** (`MapsPipHook`). Wend sends `dev.augie.wend.action.ENTER_PIP_FROM_PANEL` as an
  ordered broadcast (senders need `MANAGE_ACTIVITY_STACKS`). Maps' resumed activity gets its own
  `onUserLeaveHint`, so Maps enters its own navigation PiP from the panel, and the hook answers
  whether it did. After Maps enters or leaves PiP, the hook reports when Maps' set of shown views has
  stopped changing, through a marker `setPictureInPictureParams` call (`PipSignals`).
- **system_server** (`PanelPipHook`, `CardGrow`, `PanelReturn`), for AOSP `android-10.0.0_r47`:
  - A PiP requested from a Wend panel (an ActivityView display) moves to the main display without
    being drawn fullscreen, and Android's own shrink starts from the panel's screen bounds.
  - As the shrink lands, a still of the PiP covers Maps while Android resizes it, then crossfades
    into Maps' PiP card once Maps reports it settled (1.5 s cap).
  - A task Wend moves out of a card with `dev.augie.wend.GROW_FROM` in its options grows from the
    card to fullscreen, on the shrink's curve and duration, over a still of Wend.
  - A PiP Wend moves back into a panel grows toward the panel while fading out; the app stays hidden
    in the panel until it reports settled, then fades in.

Without the module nothing answers Wend's broadcast, Wend floats Maps by the task-switch path
(`MainActivity.floatMapsByTaskSwitch`), and the video cuts to fullscreen as before.

## Build

```
./gradlew :pip-hook:assembleDebug
```

## Install (rooted with Magisk and Zygisk)

1. Install LSPosed (Zygisk build, tested with v1.9.2) and reboot.
2. `adb install -r pip-hook/build/outputs/apk/debug/pip-hook-debug.apk`
3. Enable the module for **System Framework** and **Google Maps** in LSPosed. Without the manager
   app, the same rows can be written to `/data/adb/lspd/config/modules_config.db` as root: set
   `modules.enabled = 1` for `dev.augie.wend.piphook`, and add `scope` rows `system` and
   `com.google.android.apps.maps` (user 0) for its `mid`.
4. Remove Maps from Magisk's denylist (`magisk --denylist rm com.google.android.apps.maps`); an
   enforced denylist unloads Zygisk, and so the hook, from Maps.
5. Reboot. `/data/adb/lspd/log/modules_*.log` then shows `system_server hook installed`,
   `card grow hook installed`, `panel return hook installed`, and `Maps hook installed`.

Every system_server change needs a reboot; a Maps-only change needs Maps restarted.
