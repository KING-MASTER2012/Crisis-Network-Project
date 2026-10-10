//! End-to-end behaviour of the whole stack, measured by the simulator.

use mesh::MeshConfig;
use sim::{
    Change, LinkModel, Report, Scenario, ScenarioParams, SimConfig, builtin_scenario, run,
};

fn fast_repair(loss_permille: u32, seed: u64) -> SimConfig {
    SimConfig {
        seed,
        mesh: MeshConfig {
            resync_interval_ms: 10_000,
            want_timeout_ms: 1_000,
            ..MeshConfig::default()
        },
        link: LinkModel {
            latency_ms: 20,
            jitter_ms: 10,
            loss_permille,
        },
        ..SimConfig::default()
    }
}

fn assert_full_delivery(report: &Report) {
    assert!(
        (report.delivery_ratio() - 1.0).abs() < f64::EPSILON,
        "delivery ratio {} in\n{}",
        report.delivery_ratio(),
        report.summary()
    );
    assert_eq!(report.totals.duplicate_notifications, 0);
}

#[test]
fn twenty_nodes_over_a_lossy_network_deliver_everything_exactly_once() {
    let params = ScenarioParams {
        nodes: 20,
        messages: 10,
        seed: 7,
        settle_ms: 120_000,
        direct_permille: 0,
    };
    let scenario = builtin_scenario("crowd", &params).unwrap();
    let report = run(&fast_repair(50, 7), &scenario).unwrap();

    assert!(report.totals.packets_dropped > 0, "the network really was lossy");
    assert_full_delivery(&report);
    assert_eq!(report.totals.mesh_invalid_signatures, 0);
    assert_eq!(report.totals.mesh_peers_banned, 0);
}

#[test]
fn a_loss_free_line_delivers_everything_without_loss_or_duplicates() {
    let params = ScenarioParams {
        nodes: 8,
        messages: 6,
        seed: 3,
        settle_ms: 30_000,
        direct_permille: 0,
    };
    let scenario = builtin_scenario("line", &params).unwrap();
    let report = run(&SimConfig::default(), &scenario).unwrap();
    assert_full_delivery(&report);
    assert_eq!(report.totals.packets_dropped, 0);
    assert_eq!(report.totals.packets_sent, report.totals.packets_delivered);
}

#[test]
fn every_regular_topology_delivers() {
    for name in ["ring", "star", "grid"] {
        let params = ScenarioParams {
            nodes: 12,
            messages: 5,
            seed: 11,
            settle_ms: 30_000,
            direct_permille: 0,
        };
        let scenario = builtin_scenario(name, &params).unwrap();
        let report = run(&SimConfig::default(), &scenario).unwrap();
        assert_full_delivery(&report);
    }
}

#[test]
fn direct_messages_reach_their_recipient_and_are_acknowledged() {
    let params = ScenarioParams {
        nodes: 8,
        messages: 6,
        seed: 5,
        settle_ms: 60_000,
        direct_permille: 1000,
    };
    let scenario = builtin_scenario("line", &params).unwrap();
    let report = run(&SimConfig::default(), &scenario).unwrap();
    assert_full_delivery(&report);
    for message in &report.messages {
        assert_eq!(message.kind, "direct");
        assert!(message.acked_after_ms.is_some(), "message {} was never acknowledged", message.index);
        assert!(message.acked_after_ms >= message.latency_max_ms);
    }
}

#[test]
fn a_partitioned_network_only_delivers_within_each_half_until_it_is_healed() {
    let params = ScenarioParams {
        nodes: 12,
        messages: 6,
        seed: 9,
        settle_ms: 60_000,
        direct_permille: 0,
    };
    let healed = builtin_scenario("partition", &params).unwrap();
    assert_eq!(healed.changes.len(), 1);

    // Never healed: every broadcast stays inside its own half of six nodes.
    let split = Scenario {
        changes: Vec::new(),
        ..healed.clone()
    };
    let report = run(&SimConfig::default(), &split).unwrap();
    for message in &report.messages {
        assert_eq!(message.reached, 5, "message {}", message.index);
    }
    assert_eq!(report.totals.duplicate_notifications, 0);

    // Healed: the halves exchange their histories and everything reaches everyone.
    let report = run(&SimConfig::default(), &healed).unwrap();
    assert_full_delivery(&report);
}

#[test]
fn a_link_that_goes_down_and_comes_back_catches_up() {
    let params = ScenarioParams {
        nodes: 6,
        messages: 4,
        seed: 2,
        settle_ms: 60_000,
        direct_permille: 0,
    };
    let mut scenario = builtin_scenario("line", &params).unwrap();
    // The chain is cut in the middle for the whole workload and restored afterwards.
    let restore_at = scenario.messages.last().unwrap().at_ms + 5_000;
    scenario.changes = vec![
        sim::TimedChange {
            at_ms: 100,
            change: Change::Disconnect(2, 3),
        },
        sim::TimedChange {
            at_ms: restore_at,
            change: Change::Connect(2, 3),
        },
    ];
    let report = run(&SimConfig::default(), &scenario).unwrap();
    assert_full_delivery(&report);
}

#[test]
fn the_same_seed_gives_byte_identical_reports() {
    let params = ScenarioParams {
        nodes: 15,
        messages: 8,
        seed: 21,
        settle_ms: 40_000,
        direct_permille: 300,
    };
    let scenario = builtin_scenario("crowd", &params).unwrap();
    let config = fast_repair(80, 21);
    let a = run(&config, &scenario).unwrap().to_json();
    let b = run(&config, &scenario).unwrap().to_json();
    assert_eq!(a, b);
}

#[test]
fn a_different_seed_changes_the_network_but_not_the_outcome() {
    let params = ScenarioParams {
        nodes: 15,
        messages: 8,
        seed: 21,
        settle_ms: 90_000,
        direct_permille: 0,
    };
    let scenario = builtin_scenario("crowd", &params).unwrap();
    let a = run(&fast_repair(80, 1), &scenario).unwrap();
    let b = run(&fast_repair(80, 2), &scenario).unwrap();
    assert_ne!(a.totals.packets_sent, b.totals.packets_sent);
    assert_full_delivery(&a);
    assert_full_delivery(&b);
}

#[test]
fn invalid_configuration_is_rejected_before_running() {
    let scenario = builtin_scenario("line", &ScenarioParams::default()).unwrap();
    let bad_tick = SimConfig {
        tick_ms: 0,
        ..SimConfig::default()
    };
    assert!(run(&bad_tick, &scenario).is_err());
    let bad_loss = SimConfig {
        link: LinkModel {
            latency_ms: 10,
            jitter_ms: 0,
            loss_permille: 2000,
        },
        ..SimConfig::default()
    };
    assert!(run(&bad_loss, &scenario).is_err());
    let tiny_mtu = SimConfig {
        mtu: 10,
        ..SimConfig::default()
    };
    assert!(run(&tiny_mtu, &scenario).is_err());
    let mut broken = scenario.clone();
    broken.links.push((0, 500));
    assert!(run(&SimConfig::default(), &broken).is_err());
}
