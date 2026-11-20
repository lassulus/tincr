# Waking a sleeping `OnDemand` node from outside (phase 2, design only)

Phase 1 (`OnDemand = yes`, `IdleTimeout`) makes an idle phone cost nothing:
no meta connection, no timers, `epoll_wait` without timeout. The price is
that traffic *towards* the phone fails while it sleeps: the phone is
unreachable in the graph, so every other node answers packets for its
subnets with ICMP net-unreachable locally. Phase 2 adds one cheap, kept-alive
connection from the phone to a relay node that tells the phone "someone
wants you". Which node that is comes from configuration only (§0.1), e.g.
one public node of the mesh; nothing in code or defaults names a node,
address, port or prefix.

## 0. Wake channel vs. traffic paths

The relay connection is **only** a doorbell. It never carries data and
never serves as the phone's meta connection for normal operation:

- **Meta connection when awake:** phase 1 behaviour, unchanged. On wake the
  phone dials *all* its `ConnectTo` candidates at once and keeps the first
  that completes the handshake (`ondemand_race_won`); the rest are dropped,
  so there is one meta connection. Handshake RTT is the proximity measure:
  at home the home server (LAN, ~1 ms) beats a public relay (tens of ms); on
  mobile the nearest public node wins. If it dies, all candidates are raced
  again. The relay may also be a `ConnectTo` candidate, without any
  preference. Candidates are exactly the `ConnectTo` lines; nothing else.
- **Data:** whatever tinc already does: per-peer SPTPS over direct UDP
  (including `LocalDiscovery` to LAN addresses), relayed over the meta
  path only until UDP is confirmed. Tests:
  `netns::ondemand::ondemand_wake_prefers_near_peer` (near peer and far hub
  both `ConnectTo`; only the near one is kept, RTT shows no detour) and
  `netns::ondemand::ondemand_data_goes_direct` (only the far hub is a
  `ConnectTo`; data to a LAN peer still goes direct over UDP).
- **Wake channel:** a separate, permanently dormant connection to the
  configured relay, open the whole time the VPN is on, asleep or awake.
  Keeping it independent of the sleep state keeps the state machine
  simple; while the phone is awake nothing routes through it (below).

### 0.1 Configuration

Everything is configuration; the code knows no node names, addresses,
ports or prefixes:

- `tinc.conf` on the phone: `WakeRelay = NAME` (may be repeated; the
  first one that answers is used, the others are fallbacks). Its address,
  port and key come from `hosts/NAME` like any other node. Unset = no
  wake channel (phase 1 behaviour).
- `tinc.conf` on the relay: `WakeRelayFor = NAME` (repeatable) or a
  boolean `AcceptDormant = yes`, so a node only holds dormant connections
  it was asked to.
- Path choice when awake: the `ConnectTo` lines (race, §0) and whatever
  the graph offers for data (direct UDP, `LocalDiscovery`).
- Android: `vpn.conf` keeps only the TUN settings (addresses, routes, DNS,
  MTU). The app may expose `WakeRelay` in its UI; it writes it into
  `tinc.conf` like the `ConnectTo` list, with no default value.

## 1. Where the relay lives

### Option A (recommended): a dormant meta connection inside tincd on the relay

1. The phone opens the wake channel to the relay and sends a new request
   `DORMANT` right after `ACK` (tincr extension, gated on a `ConnOptions`
   bit negotiated in `ID`/`ACK`, so C tinc peers never see it).
2. The relay announces the phone's edge on this connection with a
   prohibitive weight (last resort). While the phone sleeps it has no other
   edges, so the mesh routes to the phone via the relay and every `REQ_KEY`
   or relayed data packet for the phone reaches the relay. While the phone is
   awake its real meta connection gives normal-weight edges, so shortest
   paths never use the dormant one. The phone itself keeps the wake channel
   out of its own graph (no edge, no routing, no gossip).
3. The relay stops forwarding gossip (`ADD_EDGE`/`DEL_EDGE`/`ADD_SUBNET`/…)
   and `KEY_CHANGED` to a dormant connection, and stops sending `PING` on it
   (liveness is the phone's job, see §3). This is the part that matters for
   battery: on a large mesh the gossip churn alone would wake the phone every
   few seconds.
4. Detection is free: the first `REQ_KEY` whose destination is the dormant
   node, or the first data packet the relay would forward to it, is the wake
   event. The relay sends one `WAKE <sender>` line on the dormant connection
   (debounced) and holds data packets for the phone for a few seconds, like
   phase 1's hold queue (`daemon/ondemand.rs`).
5. The phone runs `ondemand_wake`: the normal race over its `ConnectTo`
   candidates picks the closest meta peer; the relay is not preferred. Once
   the phone is reachable through that peer it sends its own `REQ_KEY` to
   `<sender>`, so the sender's tunnel comes up without waiting for its 10 s
   retry, and data goes direct over UDP where the NATs allow it. Packets
   the relay held are forwarded along the now-best route, which no longer
   includes the dormant edge.

Why this one: authentication, encryption and NAT traversal to the relay are
reused (the meta SPTPS session with the existing host keys); no proxy
subnets; no second service to deploy; the relay knows exactly which node is
dormant. Cost: the relay must run tincr (every other node is
unchanged and may stay C tinc), and a small protocol extension (`DORMANT`,
`WAKE`, an option bit).

Risks:
- SSSP ignores edges without a reverse, and the phone does not announce
  the dormant edge itself. The relay therefore announces both directions of
  it, flagged `IndirectData` and with the prohibitive weight, so senders
  reach the sleeping phone through the relay from the first packet instead of
  probing a stale UDP address. When the phone wakes and its own edges
  appear, those win and senders switch to direct UDP.
- The relay must bound held packets per dormant node (count and bytes) and
  give up after ~10 s with ICMP unreachable, as phase 1 does.
- A sender spraying traffic at a sleeping phone keeps it awake. That is the
  point of the feature; `IdleTimeout` counts only traffic that flows.
- Relay down: incoming wake-ups fail, outgoing traffic is unaffected
  (the race just never picks the relay).

### Option B: a separate tiny wake service on the relay host

If the relay must keep running C tinc:

- The relay advertises the phone's subnets itself with a worse weight
  (`Subnet = PHONE_ADDR/32#1000` in `hosts/RELAY`). While the phone is
  reachable its own subnet (weight 10) wins; while it sleeps lookups fall
  back to the relay, so traffic for the phone lands on the relay's TUN.
- On the relay, `ip route add PHONE_ADDR dev wake0` sends those packets to a
  TUN owned by a small `tincr-wake` service. It reads the first packet,
  pushes one byte over the phone's kept-alive connection, and drops (or
  buffers and re-injects) the rest.
- The phone authenticates to `tincr-wake` with its tinc Ed25519 key (SPTPS
  stream with the existing keys, so no TLS stack).

Downsides: nodes with `StrictSubnets` only accept the relay's extra
subnets if the distributed `hosts/RELAY` contains them; two places to
configure per phone; a second daemon to run; packets that arrive before the
wake are lost unless the service re-injects them.

## 2. How the relay detects traffic for a dormant node

Option A: in the relay's tincd, at the two places a packet or key request is
forwarded towards a node: `send_sptps_packet`/`send_sptps_data_relay`
(data) and the `REQ_KEY` forward in `daemon/gossip/keys.rs`. If the next
hop's connection is dormant: send `WAKE <sender>` (once, debounced), then
hold the packet or drop the `REQ_KEY` (the phone re-initiates it). Nothing
on the relay polls.

Option B: the kernel routes to `wake0`; the service blocks in `read()`.

## 3. Keepalive interval vs. NAT / carrier timeouts

The wake channel is phone → relay TCP. Both ends of it must survive the
carrier's NAT/firewall idle timeout:

- RFC 5382 asks NATs to keep established TCP mappings for at least 2 h 4 min,
  but carrier-grade NATs often expire them after minutes; reported values
  vary per carrier and APN from a few minutes to hours (not measured for
  the user's carriers; measure before picking defaults). Wi-Fi home routers are
  usually much more generous.
- In Doze, alarms are deferred except `setAndAllowWhileIdle` /
  `setExactAndAllowWhileIdle`, which fire at most once per nine minutes per
  app. Kernel TCP keepalive timers do not wake a suspended phone, so the
  keepalive is driven by such an alarm: the app asks tincd over the control
  socket to send one `PING` on the dormant connection.
- Start at `WakeKeepalive` (phone `tinc.conf`, generic default 25 min, not
  tied to any network), then adapt per network: if the connection dies before the
  next alarm, halve the interval for
  that network (keyed by `Network` / Wi-Fi SSID / carrier), floor 9 min; after
  a few successful rounds probe longer again. The relay must accept a dormant
  connection silent for up to ~2× the maximum interval instead of its usual
  `PingTimeout`.
- On network change (`ConnectivityManager` callback) the dormant connection
  is dead anyway; the phone redials it immediately (cheap: one TCP+SPTPS
  handshake, no gossip dump because it goes straight to `DORMANT`).

## 4. Android constraints

- The VPN app already runs a foreground service (required for `VpnService`).
  Processes in foreground-service state keep network access in Doze
  [INFERENCE: platform network policy treats foreground-service process state
  as allowed while idle; verify on the Pixel with
  `adb shell dumpsys deviceidle force-idle`]. If that does not hold, the user
  must exempt the app from battery optimisation (acceptable per the Doze
  exemption table: the app's core function is a persistent connection).
- A socket blocked in `read()` costs no CPU. An incoming TCP segment on an
  established connection wakes the application processor through the modem,
  which is exactly how FCM delivers pushes; nothing else has to stay awake.
- Wake locks are ignored in Doze; tincd doesn't need one while blocked. After
  a wake, the app should hold a partial wake lock for the few seconds the
  race and key exchange take, released when tincd reports a meta peer.
- tincd is a child process of the app's UID, so it gets the same network
  policy as the app.

## 5. Battery estimate

Order of magnitude, cellular, screen off:

- One keepalive exchange promotes the radio to connected state and holds it
  for the inactivity tail. Literature values for LTE are ~1 W for ~10 s,
  i.e. ~5–12 J per isolated exchange; 5G NR with C-DRX is somewhat lower.
- At one exchange per 25 min: ~58 exchanges/day ≈ 0.3–0.7 kJ/day. A Pixel
  9a battery holds ~5100 mAh × 3.85 V ≈ 70 kJ, so **≈0.5–1 % per day**.
  At the 9-minute floor: ≈1.5–3 % per day.
- For comparison, the always-connected setup sends at least a TCP `PING`
  every 60 s plus a UDP keepalive every 10 s (`UDPDiscoveryKeepaliveInterval`)
  per active tunnel plus mesh gossip; each of those restarts the radio
  tail, so the radio effectively never reaches idle. That is tens of percent
  per day, matching the reported drain.
- Waking for real traffic costs a short race (one TCP+SPTPS handshake per
  `ConnectTo` candidate, all but the winner cut off; keep the candidate
  list short), one full state dump from the winner (a large mesh: a few hundred
  `ADD_EDGE`/`ADD_SUBNET` lines, tens of KB), then `IdleTimeout` of normal
  operation with data on direct UDP.

## 6. Work items (for when this gets built)

1. Protocol: option bit + `DORMANT`/`WAKE` requests, documented in
   `docs/PROTOCOL.md`; C tinc never receives them.
2. Relay: `WakeRelayFor`/`AcceptDormant` option (§0.1); per-connection
   dormant flag; gossip and `PING` suppression; both-direction last-resort
   edge; wake hooks at the two forward sites; per-node bounded queue; long
   liveness window.
3. Phone daemon: the `WakeRelay` option (§0.1) that opens the dormant
   connection outside the `ConnectTo` race and outside its graph; `WAKE`
   runs `ondemand_wake` and queues a `REQ_KEY` to the sender; a control
   request "send keepalive now".
4. App: `setExactAndAllowWhileIdle` keepalive alarm with adaptive interval;
   redial on network change; short wake lock after wake.
5. Tests (netns): a third node pings the sleeping phone through the relay
   and the first ping succeeds; with a near `ConnectTo` peer present the
   woken phone's meta connection is the near peer and the ping RTT shows no
   detour via the relay; gossip churn on the relay produces zero packets on
   the dormant connection.
