//! `OnDemand = yes` on alice: no meta connection and no packets on the
//! wire while idle, the first ping dials bob and gets through, and
//! `IdleTimeout` closes everything again.

use std::env;
use std::fs;
use std::thread;
use std::time::{Duration, Instant};

use super::common::{TmpGuard, try_poll};
use super::rig::{NetNs, Node, TunPair, enter_netns, ping, tun_node};

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
