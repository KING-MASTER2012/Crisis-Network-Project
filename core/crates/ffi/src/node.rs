//! The node object: an [`engine::Engine`] behind a lock, with a byte-oriented API.

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use crypto::{ContactCard, Identity};
use engine::{Engine, EngineConfig, hex};
use mesh::{MeshConfig, PeerId};
use proto::NodeId;
use proto::limits::{MESSAGE_ID_LEN, NODE_ID_LEN};
use storage::{Config as StorageConfig, DbKey};
use zeroize::Zeroizing;

use crate::error::{CoreError, invalid_argument};
use crate::types::{
    CardDetails, ContactInfo, CoreOutput, HistoryPageInfo, NodeSettings, NodeStats, PublishOutcome,
    TrustLevel, fixed,
};

/// The default settings. Change the fields you need and pass the result to
/// [`CrisisNode::open`] or [`CrisisNode::in_memory`].
#[uniffi::export]
pub fn default_settings() -> NodeSettings {
    let config = EngineConfig::default();
    NodeSettings {
        default_lifetime_secs: config.default_lifetime_secs,
        max_store_bytes: config.storage.max_total_bytes,
        max_inbound_pinned_bytes: config.storage.max_inbound_own_bytes,
        max_peers: u32::try_from(config.mesh.max_peers).unwrap_or(u32::MAX),
    }
}

fn engine_config(settings: &NodeSettings) -> EngineConfig {
    let defaults = EngineConfig::default();
    EngineConfig {
        default_lifetime_secs: settings.default_lifetime_secs,
        storage: StorageConfig {
            max_total_bytes: settings.max_store_bytes,
            max_inbound_own_bytes: settings.max_inbound_pinned_bytes,
            ..defaults.storage
        },
        mesh: MeshConfig {
            max_peers: usize::try_from(settings.max_peers).unwrap_or(usize::MAX),
            ..defaults.mesh
        },
    }
}

/// Creates a fresh identity seed (32 random bytes).
///
/// The seed *is* the node's identity: keep it in the platform's secure store (Android
/// Keystore, Apple Keychain) and pass it to [`CrisisNode::open`] on every start. Losing
/// it loses the identity; leaking it lets anyone impersonate the node.
#[uniffi::export]
pub fn generate_seed() -> Result<Vec<u8>, CoreError> {
    let identity = Identity::generate()?;
    Ok(identity.seed().to_vec())
}

/// Creates a fresh database encryption key (32 random bytes) for [`CrisisNode::open`].
///
/// Keep it in the platform's secure store, separate from the database file.
#[uniffi::export]
pub fn generate_database_key() -> Result<Vec<u8>, CoreError> {
    let mut key = Zeroizing::new([0u8; 32]);
    getrandom::fill(&mut key[..]).map_err(|_| CoreError::Crypto {
        detail: "the operating system's randomness source failed".to_owned(),
    })?;
    Ok(key.to_vec())
}

/// Verifies a contact card (for example scanned from a QR code) without storing it.
#[uniffi::export]
pub fn inspect_contact_card(card: Vec<u8>) -> Result<CardDetails, CoreError> {
    Ok(details_of(&parse_card(&card)?))
}

fn parse_card(bytes: &[u8]) -> Result<ContactCard, CoreError> {
    ContactCard::from_bytes(bytes).map_err(|_| CoreError::InvalidContactCard)
}

fn details_of(card: &ContactCard) -> CardDetails {
    let node_id = card.node_id();
    CardDetails {
        node_id: node_id.as_bytes().to_vec(),
        node_id_hex: hex::encode(node_id.as_bytes()),
        safety_number: card.safety_number(),
    }
}

fn node_id_of(bytes: &[u8]) -> Result<NodeId, CoreError> {
    Ok(NodeId::new(fixed::<NODE_ID_LEN>("a node id", bytes)?))
}

fn identity_from(seed: Vec<u8>) -> Result<Identity, CoreError> {
    let seed = Zeroizing::new(seed);
    let array = Zeroizing::new(fixed::<32>("the identity seed", &seed)?);
    Ok(Identity::from_seed(&array)?)
}

fn db_key_from(key: Vec<u8>) -> Result<DbKey, CoreError> {
    let key = Zeroizing::new(key);
    let array = Zeroizing::new(fixed::<32>("the database key", &key)?);
    Ok(DbKey::new(*array))
}

fn to_usize(what: &str, value: u32) -> Result<usize, CoreError> {
    usize::try_from(value).map_err(|_| invalid_argument(format!("{what} is too large")))
}

/// One node of the network: identity, message store and mesh logic.
///
/// The object is thread-safe; calls are serialised internally. It never touches the
/// network, the clock or the radio: the platform feeds it events (`peer_seen`,
/// `packet_received`, `tick`) with the current time in milliseconds and carries out the
/// [`CoreOutput`] it gets back. The clock may be any monotonic millisecond clock, as long
/// as it is used consistently.
#[derive(uniffi::Object)]
pub struct CrisisNode {
    engine: Mutex<Engine>,
    node_id: NodeId,
    card: ContactCard,
}

impl CrisisNode {
    fn wrap(engine: Engine) -> Arc<Self> {
        Arc::new(Self {
            node_id: engine.node_id(),
            card: engine.contact_card(),
            engine: Mutex::new(engine),
        })
    }

    fn lock(&self) -> Result<MutexGuard<'_, Engine>, CoreError> {
        self.engine.lock().map_err(|_| CoreError::Internal {
            detail: "the node was left in an unknown state by an earlier panic; create it again"
                .to_owned(),
        })
    }
}

#[uniffi::export]
impl CrisisNode {
    /// Opens (or creates) the node's database at `database_path` and starts the node.
    ///
    /// * `seed`: the 32-byte identity seed, see [`generate_seed`].
    /// * `database_key`: the 32-byte encryption key, or `None` for an unencrypted
    ///   database. A key on a build without SQLCipher fails with `EncryptionUnavailable`
    ///   instead of silently storing data unencrypted.
    /// * `now_ms`: the current time in milliseconds.
    #[uniffi::constructor]
    pub fn open(
        seed: Vec<u8>,
        database_path: String,
        database_key: Option<Vec<u8>>,
        settings: NodeSettings,
        now_ms: u64,
    ) -> Result<Arc<Self>, CoreError> {
        let identity = identity_from(seed)?;
        let key = database_key.map(db_key_from).transpose()?;
        let engine = Engine::open(
            engine_config(&settings),
            identity,
            Path::new(&database_path),
            key.as_ref(),
            now_ms,
        )?;
        Ok(Self::wrap(engine))
    }

    /// Starts a node with a private in-memory database that disappears with the node.
    /// For tests and previews.
    #[uniffi::constructor]
    pub fn in_memory(
        seed: Vec<u8>,
        settings: NodeSettings,
        now_ms: u64,
    ) -> Result<Arc<Self>, CoreError> {
        let identity = identity_from(seed)?;
        let engine = Engine::in_memory(engine_config(&settings), identity, now_ms)?;
        Ok(Self::wrap(engine))
    }

    // ----- identity -----------------------------------------------------------------

    /// This node's address (32 bytes).
    pub fn node_id(&self) -> Vec<u8> {
        self.node_id.as_bytes().to_vec()
    }

    /// This node's address as lowercase hexadecimal.
    pub fn node_id_hex(&self) -> String {
        hex::encode(self.node_id.as_bytes())
    }

    /// This node's shareable, self-signed contact card (128 bytes). Show it as a QR code
    /// or send it over any channel; it holds no secrets.
    pub fn contact_card(&self) -> Vec<u8> {
        self.card.to_bytes().to_vec()
    }

    /// The safety number of this node's card, for comparing in person.
    pub fn safety_number(&self) -> String {
        self.card.safety_number()
    }

    // ----- events from the platform -----------------------------------------------------

    /// A neighbour is connected (or its link parameters changed). `peer` is any handle
    /// the transport chose for the connection; `link_mtu` is the largest packet the link
    /// carries in bytes (at least `core_info().min_link_mtu`).
    pub fn peer_seen(
        &self,
        now_ms: u64,
        peer: u64,
        link_mtu: u32,
    ) -> Result<CoreOutput, CoreError> {
        let mtu = to_usize("the link MTU", link_mtu)?;
        let output = self.lock()?.peer_seen(now_ms, PeerId(peer), mtu)?;
        Ok(output.into())
    }

    /// A neighbour is gone.
    pub fn peer_lost(&self, now_ms: u64, peer: u64) -> Result<CoreOutput, CoreError> {
        Ok(self.lock()?.peer_lost(now_ms, PeerId(peer))?.into())
    }

    /// One packet arrived from a neighbour. Anything may arrive; malformed or hostile
    /// packets are handled inside the core and never fail the call.
    pub fn packet_received(
        &self,
        now_ms: u64,
        peer: u64,
        bytes: Vec<u8>,
    ) -> Result<CoreOutput, CoreError> {
        Ok(self
            .lock()?
            .packet_received(now_ms, PeerId(peer), bytes)?
            .into())
    }

    /// Time passed. Call it when the deadline from the last output is reached, and
    /// whenever convenient otherwise (it is cheap).
    pub fn tick(&self, now_ms: u64) -> Result<CoreOutput, CoreError> {
        Ok(self.lock()?.tick(now_ms)?.into())
    }

    // ----- publishing ---------------------------------------------------------------------

    /// Publishes a signed, public announcement with the default lifetime.
    pub fn publish_broadcast(
        &self,
        now_ms: u64,
        payload: Vec<u8>,
    ) -> Result<PublishOutcome, CoreError> {
        let (id, output) = self.lock()?.publish_broadcast(now_ms, &payload)?;
        Ok(outcome(id.as_bytes(), output))
    }

    /// Like [`CrisisNode::publish_broadcast`] with an explicit lifetime in seconds
    /// (between 1 and `core_info().max_lifetime_secs`).
    pub fn publish_broadcast_with_lifetime(
        &self,
        now_ms: u64,
        payload: Vec<u8>,
        lifetime_secs: u32,
    ) -> Result<PublishOutcome, CoreError> {
        let (id, output) = self
            .lock()?
            .publish_broadcast_with(now_ms, &payload, lifetime_secs)?;
        Ok(outcome(id.as_bytes(), output))
    }

    /// Publishes a message encrypted for the node `to` (32 bytes), whose contact card
    /// must have been added with [`CrisisNode::add_contact`].
    pub fn publish_direct(
        &self,
        now_ms: u64,
        to: Vec<u8>,
        plaintext: Vec<u8>,
    ) -> Result<PublishOutcome, CoreError> {
        let to = node_id_of(&to)?;
        let (id, output) = self.lock()?.publish_direct(now_ms, &to, &plaintext)?;
        Ok(outcome(id.as_bytes(), output))
    }

    /// Publishes a message encrypted for the owner of a contact card, without storing
    /// the card as a contact.
    pub fn publish_direct_to_card(
        &self,
        now_ms: u64,
        card: Vec<u8>,
        plaintext: Vec<u8>,
    ) -> Result<PublishOutcome, CoreError> {
        let card = parse_card(&card)?;
        let (id, output) = self
            .lock()?
            .publish_direct_to_card(now_ms, &card, &plaintext)?;
        Ok(outcome(id.as_bytes(), output))
    }

    // ----- contacts ---------------------------------------------------------------------------

    /// Verifies a contact card received out of band and remembers it.
    pub fn add_contact(&self, now_ms: u64, card: Vec<u8>) -> Result<CardDetails, CoreError> {
        let verified = self.lock()?.add_contact(now_ms, &card)?;
        Ok(details_of(&verified))
    }

    /// Known peers, most recently recorded first.
    pub fn contacts(&self, limit: u32) -> Result<Vec<ContactInfo>, CoreError> {
        let limit = to_usize("the limit", limit)?;
        let contacts = self.lock()?.contacts(limit)?;
        Ok(contacts.into_iter().map(Into::into).collect())
    }

    /// Marks a node (32 bytes) as unknown, verified or blocked. Messages from a blocked
    /// node are dropped on arrival and never relayed.
    pub fn set_trust(
        &self,
        now_ms: u64,
        node_id: Vec<u8>,
        trust: TrustLevel,
    ) -> Result<(), CoreError> {
        let node = node_id_of(&node_id)?;
        self.lock()?.set_trust(now_ms, &node, trust.into())?;
        Ok(())
    }

    // ----- reading ---------------------------------------------------------------------------------

    /// The conversation history after log position `after_seq` (start with 0): at most
    /// `limit` broadcasts, direct messages for this node (decrypted) and messages this
    /// node wrote. Continue with `next_after_seq`.
    pub fn history(
        &self,
        after_seq: u64,
        limit: u32,
        now_ms: u64,
    ) -> Result<HistoryPageInfo, CoreError> {
        let limit = to_usize("the limit", limit)?;
        Ok(self.lock()?.history(after_seq, limit, now_ms)?.into())
    }

    /// The handles of the neighbours currently connected.
    pub fn connected_peers(&self) -> Result<Vec<u64>, CoreError> {
        Ok(self
            .lock()?
            .connected_peers()
            .into_iter()
            .map(|p| p.0)
            .collect())
    }

    /// Counters for diagnostics.
    pub fn stats(&self) -> Result<NodeStats, CoreError> {
        let engine = self.lock()?;
        let store = engine.storage().stats()?;
        Ok(NodeStats::new(
            engine.stats(),
            store.message_count,
            store.total_bytes,
        ))
    }
}

fn outcome(id: &[u8; MESSAGE_ID_LEN], output: engine::Output) -> PublishOutcome {
    PublishOutcome {
        message_id: id.to_vec(),
        output: output.into(),
    }
}
