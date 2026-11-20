# Waking a sleeping `OnDemand` node from outside (phase 2, design only)

Phase 1 (`OnDemand = yes`, `IdleTimeout`) makes an idle phone cost nothing:
no meta connection, no timers, `epoll_wait` without timeout. The price is
that traffic *towards* the phone fails while it sleeps: the phone is
unreachable in the graph, so every other node answers packets for its
subnets with ICMP net-unreachable locally. Phase 2 adds one cheap, kept-alive
connection from the phone to a relay (`neoprism`, public, already a retiolum
hub) that tells the phone "someone wants you".

## 1. Where the relay lives

### Option A (recommended): a dormant meta connection inside tincd on the hub

Before sleeping, the phone does not close its connection to the hub but
demotes it:

1. Phone sends a new request `DORMANT` (tincr extension, gated on an
   `ConnOptions` bit negotiated in `ID`/`ACK`, so C tinc peers never see it).
2. The hub keeps the connection and the phone's edges in the graph, so the
   mesh still believes the phone is reachable *via the hub*. Every other node
   keeps routing to the phone, and since the hub is the phone's only
   neighbour, every `REQ_KEY` and every relayed data packet for the phone
   reaches the hub.
3. The hub stops forwarding gossip (`ADD_EDGE`/`DEL_EDGE`/`ADD_SUBNET`/…)
   and `KEY_CHANGED` to a dormant connection, and stops sending `PING` on it
   (liveness is the phone's job, see §3). This is the part that matters for
   battery: on retiolum the gossip churn alone would wake the phone every
   few seconds.
4. Detection is free: the first `REQ_KEY` whose destination is the dormant
   node, or the first data packet the hub would forward to it, is the wake
   event. The hub forwards that `REQ_KEY` over the dormant connection
   unchanged (it *is* the wake message) and queues data packets for the
   phone for a few seconds, the same hold logic phase 1 uses on the phone
   side (`daemon/ondemand.rs`).
5. Phone-side, any line arriving on the dormant connection runs
   `ondemand_wake`, then sends `RESUME`. The hub answers with the full state
   dump it sends after `ACK` (`send_everything`) because the phone missed the
   gossip while dormant, then treats the connection as a normal one again.
   The waiting `REQ_KEY` is processed normally, so the sender's tunnel comes
   up without waiting for its 10 s `REQ_KEY` retry.

Why this one: authentication, encryption, NAT traversal of the hub address
and routing are all reused (the meta SPTPS session with the existing host
keys); no proxy subnets; no second service to deploy; the hub knows exactly
which node is dormant. Cost: neoprism must run tincr (only the hub; every
other retiolum node is unchanged and may stay C tinc), and a small protocol
extension (two requests plus an option bit).

Risks:
- While dormant the phone looks reachable, so senders will try UDP to the
  phone's last address. Those probes go nowhere until the wake; the hub's
  relay path still works because the phone's only edge is to the hub.
  Mitigation: before going dormant the phone re-announces its edge with the
  `IndirectData` option, so senders relay via the hub from the first packet
  instead of probing a dead UDP address.
- The hub has to bound queued packets per dormant node (count and bytes)
  and give up after ~10 s with ICMP unreachable, as phase 1 does.
- A sender spraying traffic at a sleeping phone keeps it awake. That is the
  point of the feature, but the phone's `IdleTimeout` should count only
  traffic that actually flows, which phase 1 already does.

### Option B: a separate tiny wake service on neoprism

If neoprism must keep running C tinc:

- neoprism advertises the phone's subnets itself with a worse weight
  (`Subnet = 10.243.x.y/32#1000` in `hosts/neoprism`). While the phone is
  reachable its own subnet (weight 10) wins; while it sleeps lookups fall
  back to neoprism, so traffic for the phone lands on neoprism's TUN.
- On neoprism, `ip route add 10.243.x.y dev wake0` sends those packets to a
  TUN owned by a small `tincr-wake` service. It reads the first packet,
  pushes one byte over the phone's kept-alive connection, and drops (or
  buffers and re-injects) the rest.
- The phone authenticates to `tincr-wake` with its tinc Ed25519 key (SPTPS
  stream with the existing keys, so no TLS stack).

Downsides: retiolum nodes with `StrictSubnets` only accept neoprism's extra
subnets if the distributed `hosts/neoprism` contains them; two places to
configure per phone; a second daemon to run; packets that arrive before the
wake are lost unless the service re-injects them.

## 2. How the hub detects traffic for a dormant node

Option A: in the hub's tincd, at the two places a packet or key request is
forwarded towards a node: `send_sptps_packet`/`send_sptps_data_relay`
(data) and the `REQ_KEY` forward in `daemon/gossip/keys.rs`. If the next
hop's connection is dormant: wake it (once, debounced), then queue the
packet or pass the `REQ_KEY` through. Nothing on the hub polls.

Option B: the kernel routes to `wake0`; the service blocks in `read()`.

## 3. Keepalive interval vs. NAT / carrier timeouts

The connection is phone → hub TCP (the existing meta connection in A). Both
ends of it must survive the carrier's NAT/firewall idle timeout:

- RFC 5382 asks NATs to keep established TCP mappings for at least 2 h 4 min,
  but carrier-grade NATs often expire them after minutes; reported values
  vary per carrier and APN from a few minutes to hours (not measured for
  lass's carriers; measure before picking defaults). Wi-Fi home routers are
  usually much more generous.
- In Doze, alarms are deferred except `setAndAllowWhileIdle` /
  `setExactAndAllowWhileIdle`, which fire at most once per nine minutes per
  app. Kernel TCP keepalive timers do not wake a suspended phone, so the
  keepalive is driven by such an alarm: the app asks tincd over the control
  socket to send one `PING` on the dormant connection.
- Start at 25 min, then adapt per network: if the connection dies before the
  next alarm, halve the interval for
  that network (keyed by `Network` / Wi-Fi SSID / carrier), floor 9 min; after
  a few successful rounds probe longer again. The hub must accept a dormant
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
  connection setup takes, released when tincd reports a connected hub.
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
  per active tunnel plus retiolum gossip; each of those restarts the radio
  tail, so the radio effectively never reaches idle. That is tens of percent
  per day, matching the reported drain.
- Waking for real traffic costs one meta handshake plus a full state dump
  (retiolum: a few hundred `ADD_EDGE`/`ADD_SUBNET` lines, tens of KB),
  then `IdleTimeout` of normal operation.

## 6. Work items (for when this gets built)

1. Protocol: option bit + `DORMANT`/`RESUME` requests, documented in
   `docs/PROTOCOL.md`; C tinc never receives them.
2. Hub: per-connection dormant flag; gossip and `PING` suppression; wake
   hooks at the two forward sites; per-node bounded queue; long liveness
   window.
3. Phone daemon: `ondemand_sleep` demotes the hub connection instead of
   closing it; any input on it wakes; `RESUME` handling; a control request
   "send keepalive now".
4. App: `setExactAndAllowWhileIdle` keepalive alarm with adaptive interval;
   redial on network change; short wake lock after wake.
5. Tests: netns test with a third node pinging the sleeping phone through
   the hub (first ping succeeds), and a test that gossip churn on the hub
   produces zero packets to the dormant phone.
