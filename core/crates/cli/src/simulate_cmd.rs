//! `crisis simulate`.

use std::io::Write;

use mesh::MeshConfig;
use sim::{LinkModel, ScenarioParams, SimConfig, builtin_scenario};

use crate::args::SimulateOptions;
use crate::error::CliError;

/// Builds the scenario and configuration described by `options`, runs them and
/// prints the result.
pub(crate) fn run(options: &SimulateOptions, out: &mut dyn Write) -> Result<(), CliError> {
    let params = ScenarioParams {
        nodes: options.nodes,
        messages: options.messages,
        seed: options.seed,
        settle_ms: options.settle_ms,
        direct_permille: options.direct_permille,
    };
    let scenario = builtin_scenario(&options.scenario, &params)?;

    let mut mesh = MeshConfig::default();
    if let Some(resync_ms) = options.resync_ms {
        mesh.resync_interval_ms = resync_ms;
    }
    let config = SimConfig {
        seed: options.seed,
        mesh,
        link: LinkModel {
            latency_ms: options.latency_ms,
            jitter_ms: options.jitter_ms,
            loss_permille: options.loss_permille,
        },
        mtu: options.mtu,
        ..SimConfig::default()
    };

    let report = sim::run(&config, &scenario)?;
    if options.json {
        writeln!(out, "{}", report.to_json())?;
    } else {
        write!(out, "{}", report.summary())?;
    }
    Ok(())
}
