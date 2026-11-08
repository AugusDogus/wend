# PX6 Maps recovery, November 1, 2026

The PX6 showed a black Maps panel with only its search field visible. The launcher
still rendered and opened its app drawer, but tapping the Maps dock icon did
nothing. No current Maps crash or ANR was found in the collected logs. A Maps
thread dump showed its main and render threads waiting, rather than a Java
application deadlock.

Restarting only Wend restored Maps rendering and dock fullscreen behavior.
Maps retained PID 1729, task 676, and its existing activity. No Maps force-stop,
data reset, launcher rollback, or vehicle reboot was performed.

## Corrected launch-state bug

`launchNew` left `launching` set until `onTaskCreated`. Android can reuse an
existing task after a task query misses it, so that callback is not guaranteed.
The flag then blocks both Home restoration and fullscreen requests indefinitely.
The initial failure's input dump still showed the stock ActivityView portal,
rather than the portal installed by `revealSurface`, consistent with restoration
not running. The original in-process flag was not directly inspected.

The launch guard now lasts only for the synchronous launch request. Task-created
callbacks remember the task ID without modifying transition state. A regression
test simulates a successful reused-task launch without a creation callback. It
failed before the fix and passes afterward.

This establishes the launch-state defect, but does not establish the exact cause
of Maps' original black renderer. Recreating its host display recovered that
symptom. Long-duration and vehicle sleep/wake behavior remain unverified.

## Validation and deployment

- Before installing the correction, hardware screenshots verified restored Maps
  content, dock fullscreen, Home return, and embedded Maps touch response.
- Opening Spotify and returning Home also preserved the restored Maps panel.
- All 53 release unit tests, release assembly, and release lint passed.
- The matching PX6 platform-signed `road-home-px6-recovery.apk` installed with
  `adb install -r` successfully. Maps was on display 0 during replacement.
- The device UI tool reported Wend opened after installation, but subsequent
  screenshot capture failed. Refreshing devices then reported the PX6 missing.
  Post-install visual checks could not be completed. Installation success is not
  evidence of post-install transition correctness.

## Reconnection check, 19:06 device time

The installed APK SHA-256 matched the recovery artifact. Maps still had PID 1729
and task 676. Opening Wend displayed Maps content normally. Tapping the
embedded Maps close control dismissed its place sheet, verifying input delivery.
The dock button then moved the same task from display 6 to display 0, confirmed
by a screenshot and the launcher log.

The fullscreen screenshot showed a black strip along the map's left edge. It
does not establish whether this was a transient redraw or a persistent defect.
The subsequent screenshot failed, and failed again after the advised device
refresh. Device interaction stopped at that point. Post-install Home return,
another app followed by Home, and transition quality remain unverified.

The next hardware check should cover those remaining flows and vehicle
sleep/wake. Preserve the existing Maps task and route while checking them.
