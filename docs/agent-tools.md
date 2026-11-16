# Agent tools

Wend runs a small MCP server so an agent (Grok Bot, Claude, a local model) can drive Google
Maps in the car. It speaks MCP's Streamable HTTP transport: each JSON-RPC message is POSTed to
`http://127.0.0.1:7311/mcp` and answered as JSON. It has no resources, prompts or event stream.

## Tools

- `navigate_to(destination)`: starts Maps' guidance to an address, `latitude,longitude` or a
  place name, through Maps' `google.navigation:` link, in place of any trip Maps guides (its stops
  too). A Maps that guides already would first ask the driver "Exit navigation?"; the Maps bridge
  module answers yes for Wend's links, through Maps' own code for that answer, so the new trip
  starts at once (see `maps-bridge/README.md`). Without the module, Maps asks.
- `add_stop(place)`: adds a stop to the trip Maps guides, through Maps' own Add stop, as on a road
  trip when a gas station comes up: Maps keeps guiding and keeps its destination, finds the place
  from the text, and reroutes through it. It needs the Maps bridge module, which calls Maps'
  navigation inside Maps (see `maps-bridge/README.md`); Wend asks it and waits up to 25 s for Maps'
  answer, which says whether the stop is on the route.
- `remove_stop(position)`: removes a stop ahead, through Maps' own navigation; Maps keeps guiding
  and keeps its destination. `position` counts the stops ahead from 1, the next, which it is when
  left out. Wend refuses a position past the stops Maps reported, where Maps would remove its
  destination.

- `remove_next_stop()`: removes the next stop ahead, the same as `remove_stop(1)`.
- `list_routes()`: the routes Maps has for the trip, the guided one first, each with its `id`,
  `guided`, `minutes` (Maps' travel time estimate) and `via` (the roads Maps names). Maps keeps
  alternatives for a while after guidance starts, then often only the guided route.
- `switch_route(route)`: guides along another of those routes, by id, through Maps' own switch.
- `end_navigation()`: ends Maps' guidance as its end-navigation button does; Maps then shows the
  trip's route with Start.

`add_stop` and `remove_stop` answer once Maps has also reported its new stops (up to 5 s more),
and list them, so a change right after another counts the stops Maps has, and the agent needs no
`trip_status` call to see them.
- `trip_status()`: the guidance Maps runs now, as Maps' own navigation state holds it (through the
  Maps bridge module): the next turn (its distance as Maps words it, and in meters and seconds;
  none while Maps does not know it, as off the route), the time and distance left, the arrival
  time, whether the car is on the route, the destination and the stops ahead.

### Why add_stop takes text

On 2026-11-15, three `add_stop` input schemas were compared on the emulator. A headless Claude
Sonnet 5.5 agent (a stand-in for Grok Bot) with web search ran each of them twice on six real
places along the route ("Add a stop at the Safeway in Ukiah.", and so on):

| Schema | Correct | Mean tokens | Mean turns |
|---|---|---|---|
| `place` (text; Maps finds it) | 12 / 12 | 4,796 | 2.2 |
| `name` + `address` | 12 / 12 | 7,296 | 2.9 |
| `name` + `latitude` + `longitude` | 8 / 12 | 8,701 | 3.3 |

With text, the agent mostly passed the name and town without searching, and Maps found the right
place. With an address, it searched for the address first. With coordinates, it searched, or
guessed them, and missed the Grace Hudson Museum by 259 and 377 m and Mendocino College by 207 m.
Maps also showed a stop given by coordinates as bare coordinates. The one text miss on the first
try ("Schat's Bakery", which Maps names "Schat's Courthouse Bakery") came back as an error, and the
agent retried with the address.

### Why remove_next_stop is its own tool

On 2026-11-15 the stop tools were compared the same way, merged (only `remove_stop(position)`,
the next stop by default, and the stops in `trip_status`) against split (also
`remove_next_stop()` and `list_stops()`). Six requests, twice each, on a trip with two stops; all
24 runs left the right stops.

| Request | Merged tokens | Split tokens |
|---|---|---|
| "Skip the next stop." | 6,877 (3 turns) | 4,673 (2 turns) |
| "What stops do I have left?" | 4,373 | 4,702 |
| Remove a named stop, or the last one | about 6,920 | about 7,355 |

With only `remove_stop`, the agent looked up the stops in `trip_status` before every removal, the
next stop's too; `remove_next_stop` saves that turn. `list_stops` saved nothing over
`trip_status` and cost about 400 tokens on every other request, for its description, so only
`remove_next_stop` was kept.

## Who can reach it

The server listens on port 7311 and answers the head unit itself (or a computer through
`adb forward tcp:7311 tcp:7311`) and any machine of the owner's tailnet, over it, at the unit's
Tailscale address or MagicDNS name (`http://px6.<tailnet>.ts.net:7311/mcp`; plain HTTP, since the
Tailscale app has no `tailscale serve`, but WireGuard encrypts it). The tailnet is the boundary:
Tailscale ties each tailnet address to one machine, and the tailnet's access rules say which
machines reach this port. Everything else, the car's own network included, gets 403. The intended
client is an MCP gateway on the tailnet (a self-hosted Executor) that agents such as Grok Bot
already use. It also rejects any request that carries an `Origin` header, so a web page cannot
drive it. It starts with Wend's process, whatever started that process. Each tool call is logged
under the `WendAgent` tag.

## Checked on the emulator (2026-11-15, Maps 26.43)

- The official MCP TypeScript client (`@modelcontextprotocol/sdk` 1.20.0) connects, lists the
  tools and calls them.
- With Maps open but not guiding, `navigate_to` started guidance at once, in Wend's panel.
- While guiding to the motel through a stop, `navigate_to` the brewery replaced the trip with no
  dialog and no gap in guidance: `trip_status` named the brewery, with no stops ahead. A link
  carrying Wend's mark sent from `adb shell` (with a forged `EXTRA_REFERRER`) still got Maps'
  dialog.
- While guiding to the motel, `add_stop("Chevron, 1099 S State St, Ukiah, CA")` added the stop
  with no change on screen but the route: 10 min / 8.4 mi became 14 min / 11 mi, `trip_status`
  listed the Chevron as a stop ahead and the motel as the destination. A second stop went before
  the Chevron. A place Maps cannot find (`qxzv plorkington zzyx 99999`) came back as an error,
  with the route unchanged.
- With the Chevron and the Safeway as stops, `remove_stop(2)` removed the Chevron, `remove_stop()`
  the Safeway, back to the motel alone (10 min / 8.4 mi); `remove_stop(3)` and a `remove_stop()`
  with no stops left were refused. Two adds and two removes back to back, with no pause, each
  answered with the stops Maps then had.
- Guiding to San Francisco, `list_routes` gave three routes (131 min via US-101 S, 134 min via
  US-101 S and I-580 E, 153 min via US-101 S and I-80 W); `switch_route` to the I-580 one made it
  the guided route, and `trip_status` said 2 hr 14 min, 134 mi. `end_navigation` ended guidance
  (`trip_status` then said guiding false), and a second call was refused.
- After Wend restarted with Maps fullscreen, the server was back without the home screen being
  created, and `trip_status` still read the trip.
