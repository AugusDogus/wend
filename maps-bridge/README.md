# maps-bridge

An optional LSPosed module that runs inside Google Maps on the Android 10 head unit and tells Wend
what Maps tells no other app. It changes nothing Maps does, except add a stop when Wend asks.
Wend works without it, with the limits below.

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
- **Its road shields** (`ShieldReporter`). The route shields Maps' own turn card draws in the next
  turn's road ("CA-20 W WEST" with a "20" shield over "CA-20 W"), which its notification lacks:
  the card's line, where each shield lies in it, and each shield rendered as Maps draws it, by
  calling `shields` on the same provider whenever the line changes, or without extras when it has
  no shield. Without it, floating Maps' card shows such a road as the notification's text.

## Adding and removing stops

`TripStops` changes the stops of the trip Maps guides through Maps' own navigation, as Maps' own
buttons do: Maps keeps guiding and keeps its destination, and reroutes. Nothing shows on screen
but the new route. Wend's `add_stop` and `remove_stop` agent tools ask with ordered broadcasts:

```
adb shell am broadcast -a dev.augie.wend.mapsbridge.ADD_STOP -p com.google.android.apps.maps \
    --es place "'Chevron, 1099 S State St, Ukiah, CA'" --ei request 1
adb shell am broadcast -a dev.augie.wend.mapsbridge.REMOVE_STOP -p com.google.android.apps.maps \
    --ei position 1 --ei request 2
# Broadcast completed: result=1
```

- Adding: Maps finds the place from its text itself (as for a directions link's text) and decides
  where among the stops ahead it goes; on the emulator each new stop went first.
- Removing: `position` counts the stops ahead from 1, the next. Maps counts its route's waypoints
  from where the car is, so the position after the last stop is the destination, which Maps
  would remove too; Wend asks only for stops Maps reported ahead.

Result 1 means the module took the ask. Maps' answer comes as a call of `stop` on Wend's provider
with `request`, `done`, and `problem` when Maps changed nothing (Maps was not guiding, or found no
such place). Maps answered within about a second on the emulator.

Maps' code is obfuscated with new names each release, so `MapsNavigation` finds what it calls by
what it does, with [DexKit](https://github.com/LuckyPray/DexKit), once per Maps build (360 ms on
the emulator), and keeps the names in Maps' `files/wend-maps-bridge-names.properties`:

- the NavigationControllerAdapter's add method, the one method that uses the string
  `NavigationControllerAdapter.addWaypointToRoute.GuidanceSDK`; its parameter is Maps' waypoint;
- its remove method, the one that uses `NavigationControllerAdapter.removeWaypointFromRoute.GuidanceSDK`
  and takes an index (another takes a waypoint);
- the waypoint's builder, which the waypoint's one static method without parameters returns, and
  its method without parameters that returns the waypoint;
- the builder's text field, the one String field of it that the code using
  `directionsShortcut_%s` writes (Maps' directions shortcut, which also routes to typed text).

The adapter itself is taken as Maps makes it while guiding, so stops can be changed from the first
guidance after Maps starts. The adapter has more: `removeNextWaypointFromRoute`, `setRoutes`,
`start`, `switchRoute` and `switchRouteWithUndo` (each names its trace the same way).

## Ending guidance and switching routes

`TripStops` also takes `dev.augie.wend.mapsbridge.END_NAVIGATION`, which ends Maps' guidance as
Maps' own end-navigation button does (the NavigationControllerAdapter's one method that takes a
boolean, called with true as that button does; Maps then shows the trip's route with Start), and
`ROUTES` and `SWITCH_ROUTE` (extra `route`), answered as above, `ROUTES` with the routes as JSON in
the extra `dev.augie.wend.mapsbridge.ROUTES`.

`MapsRoutes` reads the routes from Maps' guidance and switches through Maps' own switch. What it
reads is found the first time Wend asks (a second on the emulator) and kept in Maps'
`files/wend-maps-bridge-routes.properties`:

- the switch: the adapter's method that uses `NavigationControllerAdapter.switchRoute.GuidanceSDK`;
- the route set Maps guides by: the adapter's other method that takes a route id and says
  `Guidance not running` reads it through a field of the adapter, a field of that, a static
  getter of the guidance, and the guidance's getter of the route set;
- the guided route: what the adapter's waypoint removal (`Waypoint index to remove: %d was not in
  the range of the current route's waypoints.`) takes from the route set;
- each route's id: the String field of a route that the adapter's route switch reads;
- each route's travel time and roads: what Maps' description of an alternative route (string
  resource `ACCESSIBILITY_ALTERNATE_ROUTE_DESCRIPTION`, "Alternate route %1$s via %2$s") reads
  from a route, its one Duration getter (Maps' own copy of java.time) and the String field that
  the code of `VIA_ROADS` ("via %1$s") also reads. The resource ids are read from Maps.

Maps keeps alternatives in its guidance only for a while after guidance starts: on the emulator,
a trip to San Francisco had three routes for some seconds after starting, then the guided one
alone. A switch then worked: Maps guided along the I-580 route, and its card said 2 hr 14 min.

## Replacing the trip

While Maps guides, a navigation link makes it ask the driver "Exit navigation?" first. Maps routes
every request that would replace its screen through a reset interceptor, which shows that
question and, on yes, resets Maps' activity (stopping the guidance) and runs the request.
`TripReplacer` answers yes at once, through that same code, for links Wend sent with the extra
`dev.augie.wend.REPLACE_TRIP`, so Wend's `navigate_to` replaces the trip and its stops in one
step. Android hands Maps each intent with the app that sent it (`ReferrerIntent.mReferrer`, which
an intent's extras cannot forge); the module drops the mark from any intent not sent by Wend.

The interceptor is found with the rest (`MapsNavigation`): the class that logs `Tried to register
ResetInterceptor %s when %s is still registered.`, its one method that takes a Runnable (the
ask), its interface's method without parameters (the stop), and the one method that calls that
stop and the unregistering that logs `Tried to unregister a nonexistent ResetInterceptor.` (the
activity's reset). Without them, Maps asks the driver as usual.

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
- A Maps whose turn card is no longer a `TurnCardStepCueTextView` named `top_cue_text`, or draws
  its shields otherwise than as a `ReplacementSpan`: Wend then shows roads as text. Checked against
  Maps 26.43.
- A Maps without the reset interceptor `TripReplacer` looks for: Maps asks "Exit navigation?" before
  Wend's `navigate_to` replaces a trip, and keeps it unless the driver says yes. Checked against
  Maps 26.43.
- A Maps whose code no longer has the three things `MapsNavigation` looks for, or has several of
  one: the module logs which, and Wend's `add_stop` and `remove_stop` say Maps' stops were not found (`list_routes` and `switch_route`
  the same for routes); all else
  works. Checked against Maps 26.43.
- A Maps that stops posting a guidance notification. It is Maps' foreground-service notification,
  which Android requires of it while it navigates in the background.
