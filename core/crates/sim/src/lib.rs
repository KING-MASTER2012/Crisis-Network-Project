//! # sim
//!
//! A deterministic network simulator. It runs many [`engine::Engine`]s on one virtual
//! clock, connects them with lossy, delayed links, replays a scripted workload and
//! measures what happened. No radio, no threads and no wall-clock time are involved,
//! so a run is fully reproducible from its seed.
//!
//! * [`SimConfig`] describes the network: seed, link quality, packet size, mesh tuning.
//! * [`Scenario`] describes the world: topology, link changes over time and the
//!   messages to send. [`builtin_scenario`] offers ready-made ones (line, ring, star,
//!   grid, crowd, partition).
//! * [`run`] plays a scenario and returns a [`Report`], which can be printed or
//!   exported as JSON for analysis tools.
#![forbid(unsafe_code)]

mod error;
mod report;
mod rng;
mod runner;
mod scenario;

pub use error::{Result, SimError};
pub use report::{MessageReport, Report, Totals};
pub use rng::SimRng;
pub use runner::{LinkModel, SimConfig, run};
pub use scenario::{
    Change, Link, PlannedMessage, SCENARIO_NAMES, Scenario, ScenarioParams, Target, TimedChange,
    Workload, builtin_scenario, grid_links, line_links, plan_messages, random_links, ring_links,
    star_links,
};
