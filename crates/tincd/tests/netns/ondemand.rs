//! `OnDemand = yes` on alice: no meta connection and no packets on the
//! wire while idle, the first ping dials bob and gets through, and
//! `IdleTimeout` closes everything again.

use std::env;
use std::fs;
use std::thread;
use std::time::{Duration, Instant};

use std::process::Command;

use super::common::linux::{run_ip, run_ip_in};
use super::common::{TmpGuard, node_status, try_poll};
use super::rig::{
    ChildNetNs, NetNs, Netem, Node, TunPair, enter_netns, ping, ping_once, set_address, start_in,
    tun_node,
};

/// Both daemons listen on 127.0.0.1, so every meta or UDP packet
/// between them crosses `lo`. `/proc/net/dev` is per netns; the
/// `/sys` that bwrap binds in is the host's.
fn lo_packets() -> u64 {
    let dev = fs::read_to_string("/proc/net/dev").expect("/proc/net/dev");
    dev.lines()
        .find_map(|l| l.trim_start().strip_prefix("lo:"))
        .and_then(|counters| counters.split_whitespace().nth(9)?.parse().ok())
        .expect("lo tx_packets")
}

/// Peer rows in `dump connections` (control conns excluded).
fn peer_conns(node: &Node) -> usize {
    node.ctl()
        .dump(6)
        .iter()
        .filter(|row| {
            row.strip_prefix("18 6 ")
                .is_some_and(|b| !b.starts_with("<control>"))
        })
        .count()
}

/// `lo` stays silent for `secs` and alice holds no peer connection.
fn assert_quiet(alice: &Node, secs: u64) {
    assert_eq!(peer_conns(alice), 0, "{}", alice.log());
    let before = lo_packets();
    thread::sleep(Duration::from_secs(secs));
    let after = lo_packets();
    assert_eq!(before, after, "packets on lo while asleep\n{}", alice.log());
    assert_eq!(peer_conns(alice), 0, "{}", alice.log());
}

/// One echo request, no retries: the held first packet must make it.
fn first_ping_ok(alice: &Node, bob: &Node) {
    let started = Instant::now();
    let out = ping(&["-c", "1", "-W", "5"], "10.42.0.2");
    assert!(
        out.status.success(),
        "{}\n=== alice ===\n{}\n=== bob ===\n{}",
        String::from_utf8_lossy(&out.stdout),
        alice.log(),
        bob.log()
    );
    eprintln!("first ping after wake: {:?}", started.elapsed());
}

fn ondemand_pair(netns: NetNs, tmp: &TmpGuard) -> TunPair {
    let mut pair = TunPair::new(netns, tmp, "AutoConnect = no\n");
    pair.alice = pair.alice.with_conf("OnDemand = yes\nIdleTimeout = 3\n");
    pair.bob.write_config(&pair.alice, false);
    pair.bob.start();
    pair.alice.write_config(&pair.bob, true);
    pair.alice.start();
    pair.netns.place_devices();
    pair
}

#[test]
fn ondemand_sleep_wake_sleep() {
    let Some(netns) = enter_netns("ondemand::ondemand_sleep_wake_sleep") else {
        return;
    };
    let tmp = tmp!("ondemand");
    let pair = ondemand_pair(netns, &tmp);

    // Device up, address set, nothing dialled.
    thread::sleep(Duration::from_secs(1));
    assert_quiet(&pair.alice, 3);

    first_ping_ok(&pair.alice, &pair.bob);
    assert!(pair.alice.has_active_peer("bob"), "{}", pair.alice.log());

    // IdleTimeout = 3, checked every second.
    assert!(
        try_poll(Duration::from_secs(8), || (peer_conns(&pair.alice) == 0)
            .then_some(()))
        .is_some(),
        "still connected after IdleTimeout\n{}",
        pair.alice.log()
    );
    // Let the FIN exchange drain, then nothing.
    thread::sleep(Duration::from_millis(500));
    assert_quiet(&pair.alice, 3);

    // Second wake from sleep (not from startup) works the same.
    first_ping_ok(&pair.alice, &pair.bob);

    let (alice_log, _) = pair.finish();
    assert!(alice_log.contains("Waking up"), "{alice_log}");
    assert!(alice_log.contains("going to sleep"), "{alice_log}");
}

/// Traffic keeps alice awake past `IdleTimeout`.
#[test]
fn ondemand_traffic_keeps_awake() {
    let Some(netns) = enter_netns("ondemand::ondemand_traffic_keeps_awake") else {
        return;
    };
    let tmp = tmp!("ondemand-busy");
    let pair = ondemand_pair(netns, &tmp);

    first_ping_ok(&pair.alice, &pair.bob);
    // 6 s of one ping per second, twice the timeout.
    let out = ping(&["-c", "6", "-i", "1", "-W", "2"], "10.42.0.2");
    assert!(out.status.success(), "{}", pair.alice.log());
    assert!(pair.alice.has_active_peer("bob"), "{}", pair.alice.log());
    let (alice_log, _) = pair.finish();
    assert!(!alice_log.contains("going to sleep"), "{alice_log}");
}

/// Context switches of every thread of `pid`.
fn ctx_switches(pid: i32) -> u64 {
    let mut total = 0;
    for task in fs::read_dir(format!("/proc/{pid}/task")).expect("task dir") {
        let status = fs::read_to_string(task.unwrap().path().join("status")).unwrap_or_default();
        total += status
            .lines()
            .filter(|l| l.starts_with("voluntary_ctxt_switches") || l.starts_with("nonvoluntary"))
            .filter_map(|l| l.split_whitespace().nth(1)?.parse::<u64>().ok())
            .sum::<u64>();
    }
    total
}

fn measure(label: &str, alice: &Node, secs: u64) {
    let pid = alice.pid().as_raw();
    let (c0, p0) = (ctx_switches(pid), lo_packets());
    thread::sleep(Duration::from_secs(secs));
    let (c1, p1) = (ctx_switches(pid), lo_packets());
    eprintln!(
        "MEASURE {label}: {secs}s, alice context switches {}, packets on lo {}",
        c1 - c0,
        p1 - p0
    );
}

/// Idle cost of a hub connection with default timers vs. asleep.
/// `ONDEMAND_MEASURE_SECS` (default 60); run with `--ignored --nocapture`.
#[test]
#[ignore = "takes two minutes; prints measurements"]
fn ondemand_idle_cost() {
    let Some(netns) = enter_netns("ondemand::ondemand_idle_cost") else {
        return;
    };
    let secs = env::var("ONDEMAND_MEASURE_SECS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(60);
    let tmp = tmp!("ondemand-cost");
    // Defaults: PingInterval 60, PingTimeout 5 (beats the rig's 1).
    let defaults = "AutoConnect = no\nPingTimeout = 5\n";
    let mut pair = TunPair::new(netns, &tmp, defaults);
    pair.start_direct();
    pair.wait_validkey();
    thread::sleep(Duration::from_secs(2));
    measure("connected (default timers)", &pair.alice, secs);
    pair.alice.stop();

    let dir = tmp.path().join("sleepy");
    let mut alice = tun_node(&dir, "alice", 0xCA, "tinc0")
        .with_conf(defaults)
        .with_conf("OnDemand = yes\nIdleTimeout = 300\n");
    alice.write_config(&pair.bob, true);
    alice.start();
    thread::sleep(Duration::from_secs(2));
    measure("on-demand asleep", &alice, secs);
    assert_eq!(peer_conns(&alice), 0);
    first_ping_ok(&alice, &pair.bob);
    alice.stop();
    pair.bob.stop();
}

/// alice's end of the "far" link (another netns behind netem delay).
const FAR_OUTER: &str = "10.78.0.1";
const FAR_HUB: &str = "10.78.0.2";
const FAR_PORT: u16 = 6551;

/// alice and bob on the "LAN" (both listen on 127.0.0.1, ~0 RTT), and
/// `hub` in netns `far` behind 50 ms of netem each way. bob is
/// connected to hub; alice is asleep with `ConnectTo = hub` and, with
/// `near`, `ConnectTo = bob`.
struct NearFar {
    _far: ChildNetNs,
    _delay: Netem,
    pair: TunPair,
    hub: Node,
}

impl NearFar {
    fn start(netns: NetNs, tmp: &TmpGuard, near: bool) -> Self {
        let far = ChildNetNs::new("far");
        run_ip(&[
            "link", "add", "veth-o", "type", "veth", "peer", "name", "veth-f",
        ]);
        run_ip(&["link", "set", "veth-f", "netns", "far"]);
        run_ip(&["addr", "add", &format!("{FAR_OUTER}/24"), "dev", "veth-o"]);
        run_ip(&["link", "set", "veth-o", "up"]);
        run_ip_in(
            "far",
            &["addr", "add", &format!("{FAR_HUB}/24"), "dev", "veth-f"],
        );
        run_ip_in("far", &["link", "set", "veth-f", "up"]);
        let delay = Netem::apply("veth-o", "delay 50ms");
        let out = Command::new("ip")
            .args([
                "netns", "exec", "far", "tc", "qdisc", "add", "dev", "veth-f",
            ])
            .args(["root", "netem", "delay", "50ms"])
            .output()
            .expect("spawn tc");
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );

        // PingTimeout 5: the rig's 1 s would time out the far handshake.
        let quiet = "AutoConnect = no\nPingTimeout = 5\n";
        let mut pair = TunPair::new(netns, tmp, quiet);
        pair.alice = pair
            .alice
            .with_conf("OnDemand = yes\nIdleTimeout = 30\nConnectTo = hub\n");
        let mut hub = Node::new(tmp.path(), "hub", 0xCD).with_conf(quiet);
        hub.port = FAR_PORT;
        hub.write_config_multi(&[&pair.alice, &pair.bob], &[]);
        start_in(&mut hub, Some("far"));

        pair.bob.write_config_multi(&[&pair.alice, &hub], &[]);
        set_address(&pair.bob, &hub, FAR_HUB, FAR_PORT);
        let conf = pair.bob.confbase.join("tinc.conf");
        let text = fs::read_to_string(&conf).unwrap() + "ConnectTo = hub\n";
        fs::write(conf, text).unwrap();
        pair.bob.start();
        pair.bob.wait_for_peer("hub", true, Duration::from_secs(10));

        // Without `near`, alice only learns bob's address via hub.
        let dial: &[&Node] = if near { &[&pair.bob] } else { &[] };
        pair.alice.write_config_multi(&[&pair.bob, &hub], dial);
        set_address(&pair.alice, &hub, FAR_HUB, FAR_PORT);
        pair.alice.start();
        pair.netns.place_devices();
        Self {
            _far: far,
            _delay: delay,
            pair,
            hub,
        }
    }

    fn logs(&self) -> String {
        format!(
            "=== alice ===\n{}\n=== bob ===\n{}\n=== hub ===\n{}",
            self.pair.alice.log(),
            self.pair.bob.log(),
            self.hub.log()
        )
    }

    fn finish(mut self) {
        self.hub.stop();
        self.pair.finish();
    }
}

/// Names of alice's peer connections.
fn conn_names(node: &Node) -> Vec<String> {
    node.ctl()
        .dump(6)
        .iter()
        .filter_map(|row| row.strip_prefix("18 6 ")?.split_whitespace().next())
        .filter(|name| *name != "<control>")
        .map(str::to_owned)
        .collect()
}

/// Largest RTT of `count` pings to bob, in ms.
fn max_rtt(count: &str) -> f64 {
    let out = ping(&["-c", count, "-i", "0.2", "-W", "2"], "10.42.0.2");
    let stdout = String::from_utf8_lossy(&out.stdout);
    assert!(out.status.success(), "{stdout}");
    stdout
        .lines()
        .find_map(|l| l.split(" = ").nth(1)?.split('/').nth(2)?.parse().ok())
        .unwrap_or_else(|| panic!("no rtt line in:\n{stdout}"))
}

/// With a near and a far `ConnectTo`, the wake races both and keeps
/// only the near one; the far hub is not used for meta or data.
#[test]
fn ondemand_wake_prefers_near_peer() {
    let Some(netns) = enter_netns("ondemand::ondemand_wake_prefers_near_peer") else {
        return;
    };
    let tmp = tmp!("ondemand-near");
    let mesh = NearFar::start(netns, &tmp, true);
    thread::sleep(Duration::from_millis(500));
    assert!(conn_names(&mesh.pair.alice).is_empty(), "{}", mesh.logs());

    first_ping_ok(&mesh.pair.alice, &mesh.pair.bob);
    // Long enough for the far handshake to have finished had it run.
    thread::sleep(Duration::from_secs(2));
    assert_eq!(conn_names(&mesh.pair.alice), ["bob"], "{}", mesh.logs());
    let rtt = max_rtt("5");
    assert!(
        rtt < 50.0,
        "max rtt {rtt} ms: detour via hub?\n{}",
        mesh.logs()
    );
    assert!(
        mesh.pair.alice.log().contains("Using bob for meta traffic"),
        "{}",
        mesh.logs()
    );
    mesh.finish();
}

/// Only the far hub is a `ConnectTo`: meta goes there, but data to bob
/// (on the LAN) switches to direct UDP instead of riding the hub.
#[test]
fn ondemand_data_goes_direct() {
    let Some(netns) = enter_netns("ondemand::ondemand_data_goes_direct") else {
        return;
    };
    let tmp = tmp!("ondemand-direct");
    let mesh = NearFar::start(netns, &tmp, false);
    first_ping_ok(&mesh.pair.alice, &mesh.pair.bob);
    assert_eq!(conn_names(&mesh.pair.alice), ["hub"], "{}", mesh.logs());
    // UDP confirmed (status bit 0x80) to bob within a few pings.
    let udp = try_poll(Duration::from_secs(10), || {
        ping_once("10.42.0.2");
        node_status(&mesh.pair.alice.ctl().dump(3), "bob")
            .is_some_and(|s| s & 0x80 != 0)
            .then_some(())
    });
    assert!(udp.is_some(), "no direct UDP to bob\n{}", mesh.logs());
    // Via hub every echo takes >= 2 x 100 ms.
    let rtt = max_rtt("5");
    assert!(
        rtt < 50.0,
        "max rtt {rtt} ms: detour via hub?\n{}",
        mesh.logs()
    );
    mesh.finish();
}
