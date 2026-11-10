# maps-bridge

An optional LSPosed module that runs inside Google Maps on the Android 10 head unit and tells Wend
two things Maps tells no other app. It changes nothing Maps does. Wend works without it, with the
limits below.

## What it reports

- **Where the trip goes** (`TripReporter`, `TripFile`). Maps saves the directions it navigates by
  in its own files (`files/saved_directions.data.cs`,
  `files/directions_as_changed_in_navigation.data.cs`), within 3 s of a trip starting and again
  when its route or stops change. The module reads the destination out of them (the last waypoint:
  its name and address, or its coordinates) and the stops still ahead (the waypoints between it and
  the first, which is the origin or the stop Maps last went on from), and calls `trip` on Wend's
  provider `content://dev.augie.wend.mapstrips`. Without it, Wend cannot resume a trip that Maps does not
  restore: Maps restores none for a trip within about 1 km of its end.
- **Its guidance** (`GuidanceReporter`). Each guidance notification Maps builds (the turn's arrow,
  the distance to it, the road, the trip's time), handed to Wend as Maps posts it, by calling
  `guidance` on the same provider, and once more without it when Maps takes it down. It is read in
  Maps, before Android's notification service: it reaches Wend with Maps' notifications turned
  off, where Android would deliver a notification listener nothing, so floating Maps keeps its
  next-turn card, its "Arrived" card, and resuming after Maps dies.

Wend takes these calls from Maps' own uid only (`MapsTrips`). The module holds no stable connection
to Wend, so Wend's process dying does not take Maps with it.

## Asking it from adb

Any sender that may manage activity stacks (Wend, `adb shell`) can ask where Maps is going:

```
adb shell am broadcast -a dev.augie.wend.mapsbridge.TRIP -p com.google.android.apps.maps
# Broadcast completed: result=1, data="Motel 6 Ukiah, CA - North, 1340 N State St, Ukiah, CA 95482", extras: ...
```

Result 0 means Maps has no saved trip. The extras are `stops` (those still ahead, in order),
`latitude`, `longitude` (when Maps saved them) and `savedAt` (ms). Asking also makes the module
report to Wend again. Maps must be running. Sending Maps somewhere needs no module:

```
adb shell am start -a android.intent.action.VIEW -d "google.navigation:q=1340+N+State+St,+Ukiah,+CA"
```

## Installing

Needs root with LSPosed (Zygisk). Build `:maps-bridge:assembleDebug`, install the APK, enable the
module in LSPosed for Google Maps, and restart Maps. Its log tag is `wend-maps-bridge`.

## What can break it

- A Maps that saves its directions in another file or shape: `TripFile` then finds no destination,
  and Wend resumes trips as without the module (it presses Start where Maps restores a route).
  Checked against Maps 26.43.
- A Maps that stops posting a guidance notification. It is Maps' foreground-service notification,
  which Android requires of it while it navigates in the background.
