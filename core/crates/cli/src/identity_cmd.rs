//! `crisis identity`.

use std::io::Write;

use crypto::load_or_create;
use engine::hex;

use crate::args::IdentityOptions;
use crate::error::CliError;
use crate::keystore::FileKeyStore;

/// Creates the identity in the key file when it is missing and prints what can be shared.
pub(crate) fn run(options: &IdentityOptions, out: &mut dyn Write) -> Result<(), CliError> {
    let store = FileKeyStore::new(options.file.clone());
    let identity = load_or_create(&store)?;
    let card = identity.contact_card();
    writeln!(out, "key file:       {}", store.path().display())?;
    writeln!(out, "node id:        {}", identity.node_id())?;
    writeln!(out, "safety number:  {}", card.safety_number())?;
    writeln!(out, "contact card:   {}", hex::encode(&card.to_bytes()))?;
    Ok(())
}
