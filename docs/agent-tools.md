# Agent tools

Wend runs a small MCP server so an agent (Grok Bot, Claude, a local model) can drive Google
Maps in the car. It speaks MCP's Streamable HTTP transport: each JSON-RPC message is POSTed to
`http://127.0.0.1:7311/mcp` and answered as JSON. It has no resources, prompts or event stream.

## Tools

- `navigate_to(destination)`: starts Maps' guidance to an address, `latitude,longitude` or a
  place name, through Maps' `google.navigation:` link. If Maps is guiding already, it would ask
  the driver "Exit navigation?" and keep its route when nobody answers. So Wend first sends the
  "Exit navigation" action of Maps' own guidance notification, and opens the link once the
  guidance has ended (or after 3 s).
- `add_stop(place)`: adds a stop to the trip Maps guides, through Maps' own Add stop, as on a road
  trip when a gas station comes up: Maps keeps guiding and keeps its destination, finds the place
  from the text, and reroutes through it. It needs the Maps bridge module, which calls Maps'
  navigation inside Maps (see `maps-bridge/README.md`); Wend asks it and waits up to 25 s for Maps'
  answer, which says whether the stop is on the route.
- `trip_status()`: the guidance Maps runs now, as its notification shows it: the next turn, the
  time and distance left, the arrival time. With the Maps bridge module it also gives the
  destination and the stops ahead.

## Who can reach it

The server listens on the loopback interface only, so only the head unit itself can reach it,
or a computer through `adb forward tcp:7311 tcp:7311`. It rejects any request that carries an
`Origin` header, so a web page the unit opens cannot drive it. It starts with Wend's process,
whatever started that process. Each tool call is logged under the `WendAgent` tag.

Reaching it from a cloud agent such as Grok Bot means exposing it beyond loopback. That needs
sign-in first, and is not done yet.

## Checked on the emulator (2026-11-15, Maps 26.43)

- The official MCP TypeScript client (`@modelcontextprotocol/sdk` 1.20.0) connects, lists the
  tools and calls them.
- With Maps open but not guiding, `navigate_to` started guidance at once, in Wend's panel.
- While guiding to one place, `navigate_to` another place ended that route and guided to the new
  one, with no dialog. `trip_status` named the new destination.
- While guiding to the motel, `add_stop("Chevron, 1099 S State St, Ukiah, CA")` added the stop
  with no change on screen but the route: 10 min / 8.4 mi became 14 min / 11 mi, `trip_status`
  listed the Chevron as a stop ahead and the motel as the destination. A second stop went before
  the Chevron. A place Maps cannot find (`qxzv plorkington zzyx 99999`) came back as an error,
  with the route unchanged.
- After Wend restarted with Maps fullscreen, the server was back without the home screen being
  created, and `trip_status` still read the trip.
