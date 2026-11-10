# Website routing formats

Both apps accept exact hosts (`example.com`, `full:example.com`), suffixes (`*.example.com`, `.example.com`, `+.example.com`, `domain:example.com`, `suffix:example.com`), single-label zones (`ru`, `.ru`, `*.ru`, `zone:ru`, `suffix:ru`), HTTP/HTTPS addresses, IDN, IPv4/IPv6 literals, CIDRs and ordered same-family IP ranges.

A suffix includes its apex and every subdomain. A multi-label name such as `co.uk` is exact unless an explicit suffix marker is used. HTTP/HTTPS input selects the entire host; scheme, port, path, query and fragment are not match conditions. Credentials and arbitrary/internal-label wildcards are rejected. IDN uses the same browser-compatible ASCII identity as the VPN parser. IP parsing is literal and performs no hostname resolution.

Ranges are converted to an exact CIDR cover without iterating addresses; a single IPv6 range needs at most 254 CIDRs. Guard preserves one stored rule and splits only its runtime CIDR representation into at most 64 CIDRs per native route, retaining selectors, action and order. App input is bounded to 2048 characters; the save adapter refuses invalid members, more than 4096 input tokens or expanded CIDRs before persisting anything. Existing DB rules and identities are not migrated.

`kw:` and `re:` cannot be represented by the current FoxCore policy translator. New input is refused explicitly; existing Guard rules remain visible with a warning and are not silently removed. This changes no routing precedence, DNS/UID attribution, platform gates, profiles or runtime proof ownership. WireGuard domain rules keep their existing Android platform/DNS requirements.

The in-app notification type icon is an inline text placeholder sized to the resolved font capital height. Wrapped lines resume at the normal full text width; timers, swipe dismissal, haptics, window handoff and system notification channels remain unchanged.
