# youtube-pip-hook

An in-process LSPosed/Zygisk module that makes **Google YouTube** enter its **own**
native picture-in-picture (its real mini-player, 16:9) when it is backgrounded
during playback. It exists only because YouTube, unlike Google Maps, gates PiP on
its own in-app setting and entitlement and so does not call
`enterPictureInPictureMode` on command, even though Wend has already made
everything Android needs true (`supportsPictureInPicture`,
`supportsEnterPipOnTaskSwitch`, and the `PICTURE_IN_PICTURE` app-op allowed — see
`docs/floating-windows.md`, "Known limitation: YouTube declines its own PiP").

Google **Maps** needs no hook: it enters its own navigation PiP card via the clean
Wend path and this module leaves it untouched.

## What it does

On the YouTube process it hooks the watch activity's `onUserLeaveHint` (and
`onPause` as a fallback) and calls the activity's own `enterPictureInPictureMode`
with a 16:9 `PictureInPictureParams`. YouTube then renders **its own** player PiP;
the hook only triggers the entry YouTube declines to make. It never repackages or
re-signs YouTube (that would break the Google signature and risk the account) —
the signature stays intact because the hook runs in-process at runtime.

The decision of *when* to force PiP is `PipDecision.shouldEnter`, kept free of the
Xposed API so it is unit-tested with plain JUnit (`PipDecisionTest`): force PiP only
for the watch activity, only when it is finishing-less, not already in PiP, and the
launcher that triggered the task switch is Wend.

## Scope

- Package: `com.google.android.youtube`
- Activity: `com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity`

## Build

This module is intentionally **not** part of the Wend app's Gradle build (it ships
in the YouTube process, not Wend's, and depends on the Xposed API). Build it with
the XposedBridge API on the compile classpath only:

```
repositories { maven("https://api.xposed.info/") }
dependencies { compileOnly("de.robv.android.xposed:api:82") }
```

`src/main/assets/xposed_init` names the entry class, as LSPosed requires.

## Install on the target (PX6 or a rebootable AVD)

LSPosed needs a Magisk-rooted system with Zygisk, which requires a reboot. It was
therefore **not** installed on the live-navigation emulator used to verify Maps
(rebooting would have ended the SF→Ukiah route that had to stay alive). On the
rooted PX6:

1. Flash Magisk, enable Zygisk, install the LSPosed Zygisk module, reboot.
2. Install this module's APK, enable it in LSPosed, and scope it to
   `com.google.android.youtube` only.
3. Force-stop YouTube so it reloads with the hook. Maps is out of scope and
   unaffected.

Verify: with a video playing in Wend's card, open Maps fullscreen — YouTube should
now show its own mini-player PiP (16:9), matching Maps' own nav-card PiP.
