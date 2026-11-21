//! `OnDemand = yes`: no meta connections and no armed timers until the device
//! has a packet for the VPN. That packet races all `ConnectTo` candidates and
//! keeps the first to ACK (the closest); data then goes direct over UDP as usual.
//! Packets without a route or key yet are held and replayed. After `IdleTimeout`
//! seconds in which the device initiated nothing, everything is closed and
//! `epoll_wait` blocks.

#![forbid(unsafe_code)]

use std::collections::VecDeque;
use std::mem;
use std::time::{Duration, Instant};

use super::intervals::{HOUSEKEEP_SWEEP, PERIODIC_TICK, PING_SWEEP};
use super::{ConnId, Daemon, TimerWhat};
use crate::clock;
use crate::event::{TimerHandle, Timers};
use crate::graph::NodeId;
use crate::outgoing::{OutOrigin, OutState, Outgoing, OutgoingId};
use crate::packet::{ETH_P_IP, ETH_P_IPV6, ethertype};
use crate::route_decide::RouteResult;
use crate::subnet_tree::SubnetTree;
use crate::tunnel::TunnelState;
use slotmap::SlotMap;
use std::net::Ipv4Addr;
use tinc_proto::Subnet;

/// How long a packet waits for its route and tunnel key. Covers a meta
/// handshake plus `REQ_KEY` over a slow mobile link; past it the packet
/// takes the normal path (ICMP unreachable or drop).
pub(crate) const HOLD_TIMEOUT: Duration = Duration::from_secs(10);

/// Held-packet cap; beyond it packets take the normal path.
const HOLD_MAX: usize = 128;

struct Held {
    deadline: Instant,
    frame: Box<[u8]>,
}

/// Sleep/wake state. `Some` on the daemon iff `OnDemand = yes`.
pub(crate) struct OnDemand {
    pub(crate) asleep: bool,
    idle_timer: TimerHandle,
    hold_timer: TimerHandle,
    /// Set by a directed unicast packet in either direction ([`unicast`]),
    /// cleared by the idle check; `last_active` is when it was last seen.
    activity: bool,
    last_active: Instant,
    /// Start of the window in which unroutable packets are held.
    woke_at: Instant,
    held: VecDeque<Held>,
}

impl OnDemand {
    pub(crate) fn new(timers: &mut Timers<TimerWhat>) -> Self {
        let now = timers.now();
        Self {
            asleep: true,
            idle_timer: timers.add(TimerWhat::OnDemandIdle),
            hold_timer: timers.add(TimerWhat::OnDemandHold),
            activity: false,
            last_active: now,
            woke_at: now,
            held: VecDeque::new(),
        }
    }

    pub(crate) fn has_held(&self) -> bool {
        !self.held.is_empty()
    }

    /// Directed unicast passed through ([`unicast`]); keeps it awake.
    pub(crate) fn note_activity(&mut self) {
        self.activity = true;
    }
}

/// Whether a router-mode frame has a directed unicast destination: not
/// multicast (`224/4`, `ff00::/8`), `255.255.255.255`, the broadcast address of
/// a known IPv4 subnet, or IPv6 link-local. Only these wake an `OnDemand`
/// daemon or keep it awake, in either direction, so multicast discovery
/// (SSDP, MLD, LAN sync beacons) on a busy mesh cannot.
pub(crate) fn unicast(data: &[u8], subnets: &SubnetTree) -> bool {
    match ethertype(data) {
        Some(ETH_P_IP) => {
            let Some(&[a, b, c, d]) = data.get(30..34) else {
                return false;
            };
            let dst = Ipv4Addr::new(a, b, c, d);
            // 224/4 multicast and above (240/4, 255.255.255.255); an
            // ownerless subnet is a `BroadcastSubnet`.
            a < 224
                && !subnets.lookup_ipv4(dst, |_| true).is_some_and(|(s, owner)| {
                    owner.is_none()
                        || matches!(*s, Subnet::V4 { addr, prefix, .. }
                            if prefix <= 30 && dst.to_bits() == addr.to_bits() | (u32::MAX >> prefix))
                })
        }
        // ff00::/8 multicast, fe80::/10 link-local.
        Some(ETH_P_IPV6) => {
            matches!(data.get(38..40), Some(&[a, b]) if a != 0xff && !(a == 0xfe && b & 0xc0 == 0x80))
        }
        _ => false,
    }
}

impl Daemon {
    /// Disarm the timers that would wake an idle daemon.
    pub(super) fn ondemand_disarm_timers(&mut self) {
        self.timers.del(&self.pingtimer);
        self.timers.del(&self.age_timer);
        self.timers.del(&self.periodictimer);
        self.timers.del(&self.keyexpire_timer);
    }

    /// Arm the periodic timers and dial every outgoing slot.
    pub(super) fn ondemand_wake(&mut self, why: &str) {
        let now = self.timers.now();
        let Some(od) = self.ondemand.as_mut() else {
            return;
        };
        if !od.asleep {
            return;
        }
        log::info!(target: "tincd", "Waking up: {why}");
        od.asleep = false;
        od.woke_at = now;
        od.last_active = now;
        od.activity = false;
        let check = self.idle_check_interval();
        if let Some(od) = &self.ondemand {
            self.timers.set(&od.idle_timer, check);
        }
        // The ping sweep reads a long gap as host suspend and would
        // close the conns we are about to open.
        self.last_periodic_run_time = clock::boottime();
        self.timers.set(&self.pingtimer, PING_SWEEP);
        self.timers.set(&self.age_timer, HOUSEKEEP_SWEEP);
        self.timers.set(&self.periodictimer, PERIODIC_TICK);
        self.timers.set(
            &self.keyexpire_timer,
            Duration::from_secs(u64::from(self.settings.keylifetime)),
        );
        self.ondemand_race();
    }

    /// Dial every idle `ConnectTo` slot at once. The first to reach
    /// ACK is the closest by handshake RTT (a LAN peer beats a far
    /// hub) and `ondemand_race_won` cancels the rest.
    fn ondemand_race(&mut self) {
        let oids: Vec<_> = self
            .outgoings
            .iter()
            .filter(|(_, o)| o.origin == OutOrigin::ConfigConnectTo)
            .map(|(oid, _)| oid)
            .collect();
        for oid in oids {
            if let Some(o) = self.outgoings.get_mut(oid) {
                o.timeout = 0;
            }
            self.setup_outgoing_connection(oid);
        }
    }

    /// `id` (a `ConnectTo` conn) just reached ACK: drop the other
    /// candidates, dialling or connected, and park their slots. One
    /// meta connection is enough; data goes direct over UDP.
    pub(super) fn ondemand_race_won(&mut self, id: ConnId) {
        if self.ondemand.as_ref().is_none_or(|od| od.asleep) {
            return;
        }
        let is_connect_to = |o: Option<OutgoingId>, outgoings: &SlotMap<OutgoingId, Outgoing>| {
            o.and_then(|oid| outgoings.get(oid))
                .is_some_and(|o| o.origin == OutOrigin::ConfigConnectTo)
        };
        if !self
            .conns
            .get(id)
            .is_some_and(|c| is_connect_to(c.outgoing, &self.outgoings))
        {
            return;
        }
        let losers: Vec<ConnId> = self
            .conns
            .iter()
            .filter(|&(cid, c)| cid != id && is_connect_to(c.outgoing, &self.outgoings))
            .map(|(cid, _)| cid)
            .collect();
        for o in self.outgoings.values_mut() {
            if o.origin == OutOrigin::ConfigConnectTo && o.state.conn() != Some(id) {
                o.state = OutState::Idle;
            }
        }
        for cid in losers {
            // Cleared so `terminate` does not redial the slot.
            if let Some(c) = self.conns.get_mut(cid) {
                c.outgoing = None;
            }
            self.terminate(cid);
        }
        if let Some(c) = self.conns.get(id) {
            log::info!(target: "tincd", "Using {} for meta traffic", c.name);
        }
    }

    /// The last `ConnectTo` conn died while awake: race all candidates
    /// again instead of retrying only the one that failed.
    pub(super) fn ondemand_conn_lost(&mut self) {
        if self.ondemand.as_ref().is_none_or(|od| od.asleep) {
            return;
        }
        // Only an ACKed conn counts: `terminate` may have redialled
        // the failed slot, which must not stop the race.
        let any_left = self.conns.values().any(|c| {
            c.active
                && c.outgoing
                    .and_then(|oid| self.outgoings.get(oid))
                    .is_some_and(|o| o.origin == OutOrigin::ConfigConnectTo)
        });
        if !any_left {
            self.ondemand_race();
        }
    }

    /// Close every peer connection, park the outgoing slots and disarm
    /// all timers. Control connections stay.
    fn ondemand_sleep(&mut self) {
        log::info!(target: "tincd",
                   "No traffic for {} seconds, going to sleep", self.settings.idle_timeout);
        // Asleep first: `terminate` must not re-race. Idle slots do
        // not redial; dropping a `Backoff` handle cancels its timer.
        if let Some(od) = self.ondemand.as_mut() {
            od.asleep = true;
        }
        for o in self.outgoings.values_mut() {
            o.state = OutState::Idle;
        }
        let peers: Vec<ConnId> = self
            .conns
            .iter()
            .filter(|(_, c)| !c.control)
            .map(|(id, _)| id)
            .collect();
        for id in peers {
            self.terminate(id);
        }
        self.ondemand_disarm_timers();
        if let Some(od) = self.ondemand.as_mut() {
            od.held.clear();
            self.timers.del(&od.idle_timer);
            self.timers.del(&od.hold_timer);
        }
    }

    /// A quarter of `IdleTimeout`, at least 1s: sleep starts at most
    /// 25% late, and an awake daemon checks rarely.
    fn idle_check_interval(&self) -> Duration {
        Duration::from_secs(u64::from(self.settings.idle_timeout / 4).max(1))
    }

    /// A peer's packet was delivered to the device; directed unicast
    /// (not multicast or broadcast) counts as activity.
    pub(super) fn ondemand_inbound(&mut self, frame: &[u8]) {
        if let Some(od) = self.ondemand.as_mut()
            && unicast(frame, &self.subnets)
        {
            od.note_activity();
        }
    }

    /// Sleep after `IdleTimeout` without directed unicast in either
    /// direction. Multicast and broadcast (discovery beacons, MLD, SSDP)
    /// never count, so a chatty mesh cannot keep the radio up.
    pub(super) fn on_ondemand_idle(&mut self) {
        let now = self.timers.now();
        let idle = Duration::from_secs(u64::from(self.settings.idle_timeout));
        let check = self.idle_check_interval();
        let Some(od) = self.ondemand.as_mut() else {
            return;
        };
        if mem::take(&mut od.activity) || od.has_held() {
            od.last_active = now;
        }
        let quiet = now.saturating_duration_since(od.last_active);
        let Some(left) = idle.checked_sub(quiet).filter(|d| !d.is_zero()) else {
            self.ondemand_sleep();
            return;
        };
        let next = check.min(left);
        self.timers.set(&od.idle_timer, next);
    }

    /// Whether a device packet with this route must wait. A packet to a
    /// remote owner waits for the tunnel key unless it can ride the
    /// direct meta connection (the `PACKET 17` path in
    /// `send_sptps_packet`); an unroutable one waits only while the
    /// first connection after waking comes up.
    fn must_wait(&self, result: &RouteResult<NodeId>, len: usize, unroutable_ok: bool) -> bool {
        match *result {
            RouteResult::Forward { to } if to != self.myself => {
                let tunnel = self.dp.tunnels.get(&to);
                if tunnel.is_some_and(|t| t.status.validkey) {
                    return false;
                }
                let direct = self.nodes.get(&to).is_some_and(|ns| ns.conn.is_some());
                !(direct && len > usize::from(tunnel.map_or(0, TunnelState::minmtu)))
            }
            RouteResult::Unreachable { .. } => unroutable_ok,
            _ => false,
        }
    }

    /// Kick the key exchange a waiting packet needs.
    fn kick_tunnel(&mut self, result: &RouteResult<NodeId>) -> bool {
        match *result {
            RouteResult::Forward { to } => self.try_tx(to, true),
            _ => false,
        }
    }

    /// Device-packet hook in `forward_packet`, after routing. Wakes the
    /// daemon on unicast traffic; `true` means the packet was held.
    pub(super) fn ondemand_intercept(
        &mut self,
        result: &RouteResult<NodeId>,
        data: &[u8],
        nw: &mut bool,
    ) -> bool {
        if !unicast(data, &self.subnets) {
            return false;
        }
        if self.ondemand.as_ref().is_some_and(|od| od.asleep) {
            self.ondemand_wake("packet from the device");
        }
        if let Some(od) = self.ondemand.as_mut() {
            od.note_activity();
        }
        let now = self.timers.now();
        let Some(od) = self.ondemand.as_ref() else {
            return false;
        };
        let in_window = now < od.woke_at + HOLD_TIMEOUT;
        if od.held.len() >= HOLD_MAX || !self.must_wait(result, data.len(), in_window) {
            return false;
        }
        *nw |= self.kick_tunnel(result);
        let deadline = now + HOLD_TIMEOUT;
        if let Some(od) = self.ondemand.as_mut() {
            if od.held.is_empty() {
                self.timers.set(&od.hold_timer, HOLD_TIMEOUT);
            }
            od.held.push_back(Held {
                deadline,
                frame: data.into(),
            });
        }
        true
    }

    /// Re-route held packets: send what can go, keep what still waits,
    /// and give expired ones the normal (ICMP/drop) treatment. Runs
    /// after each loop turn while anything is held, and on the hold
    /// timer.
    pub(super) fn ondemand_flush(&mut self) {
        let Some(od) = self.ondemand.as_mut() else {
            return;
        };
        let held = mem::take(&mut od.held);
        let now = self.timers.now();
        let mut keep = VecDeque::new();
        let mut nw = false;
        for mut h in held {
            let result = self.route_ip(&h.frame);
            if now < h.deadline && self.must_wait(&result, h.frame.len(), true) {
                nw |= self.kick_tunnel(&result);
                keep.push_back(h);
            } else {
                nw |= self.dispatch_route_result(result, &mut h.frame, None);
            }
        }
        if let Some(od) = self.ondemand.as_mut() {
            match keep.front() {
                Some(h) => self
                    .timers
                    .set(&od.hold_timer, h.deadline.saturating_duration_since(now)),
                None => self.timers.del(&od.hold_timer),
            }
            od.held = keep;
        }
        if nw {
            self.maybe_set_write_any();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v4(dst: [u8; 4]) -> Vec<u8> {
        let mut f = vec![0u8; 34];
        f[12..14].copy_from_slice(&ETH_P_IP.to_be_bytes());
        f[30..34].copy_from_slice(&dst);
        f
    }

    fn v6(dst: [u8; 2]) -> Vec<u8> {
        let mut f = vec![0u8; 54];
        f[12..14].copy_from_slice(&ETH_P_IPV6.to_be_bytes());
        f[38..40].copy_from_slice(&dst);
        f
    }

    /// The tree a node sees: its own /32, a /16 owned by a peer, and the
    /// built-in broadcast subnets.
    fn tree() -> SubnetTree {
        let mut t = SubnetTree::new();
        t.add("10.66.42.42/32".parse().unwrap(), "phone".into());
        t.add("10.66.0.0/16".parse().unwrap(), "hub".into());
        for s in ["255.255.255.255", "224.0.0.0/4", "ff00::/8"] {
            t.add_broadcast(s.parse().unwrap());
        }
        t
    }

    #[test]
    fn only_directed_unicast_counts() {
        let t = tree();
        assert!(unicast(&v4([10, 66, 42, 42]), &t));
        assert!(unicast(&v4([10, 66, 0, 1]), &t));
        assert!(
            unicast(&v4([192, 0, 2, 1]), &t),
            "unknown dst is still unicast"
        );
        assert!(!unicast(&v4([10, 66, 255, 255]), &t), "subnet broadcast");
        assert!(!unicast(&v4([224, 0, 0, 22]), &t), "IGMP");
        assert!(!unicast(&v4([239, 255, 255, 250]), &t), "SSDP");
        assert!(!unicast(&v4([255, 255, 255, 255]), &t));
        assert!(unicast(&v6([0x00, 0x42]), &t));
        assert!(!unicast(&v6([0xff, 0x12]), &t), "LAN discovery multicast");
        assert!(!unicast(&v6([0xff, 0x02]), &t), "MLD");
        assert!(!unicast(&v6([0xfe, 0x80]), &t), "link-local");
        assert!(unicast(&v6([0xfe, 0xc0]), &t));
        assert!(!unicast(&v4([10, 0, 0, 1])[..20], &t), "truncated");
    }
}
