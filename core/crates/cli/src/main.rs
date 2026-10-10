//! The `crisis` command-line client and network simulator.
//!
//! The binary only forwards its arguments to [`cli::run`] and turns the result into an
//! exit code: 0 on success, 2 for a usage error, 1 for any other failure.

use std::io::Write;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut stdout = std::io::stdout();
    let result = cli::run(&args, &mut stdout);
    // Nothing useful can be done if flushing fails at exit.
    stdout.flush().ok();
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(cli::CliError::Usage(message)) => {
            eprintln!("error: {message}\nrun `crisis help` for usage");
            ExitCode::from(2)
        }
        Err(other) => {
            eprintln!("error: {other}");
            ExitCode::FAILURE
        }
    }
}
