//! Dugum ve mesaj kimlikleri.

use core::fmt;

use crate::limits::{MESSAGE_ID_LEN, NODE_ID_LEN};

fn write_hex(bytes: &[u8], f: &mut fmt::Formatter<'_>) -> fmt::Result {
    for b in bytes {
        write!(f, "{b:02x}")?;
    }
    Ok(())
}

/// Bir dugumun kimligi = Ed25519 acik (imza) anahtari.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct NodeId([u8; NODE_ID_LEN]);

impl NodeId {
    /// Ham baytlardan olusturur (gecerli bir Ed25519 noktasi oldugu burada denetlenmez;
    /// bunu imza dogrulamasi yapar).
    pub const fn new(bytes: [u8; NODE_ID_LEN]) -> Self {
        Self(bytes)
    }

    /// Ham baytlar.
    pub const fn as_bytes(&self) -> &[u8; NODE_ID_LEN] {
        &self.0
    }
}

impl From<[u8; NODE_ID_LEN]> for NodeId {
    fn from(b: [u8; NODE_ID_LEN]) -> Self {
        Self(b)
    }
}

impl fmt::Display for NodeId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write_hex(&self.0, f)
    }
}

impl fmt::Debug for NodeId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "NodeId(")?;
        write_hex(&self.0[..4], f)?;
        write!(f, "..)")
    }
}

/// Mesaj kimligi: `SHA-256(DOMAIN_MSG_ID || imzali_govde)`.
///
/// Ayni mesaj ag uzerinde kac yoldan gelirse gelsin ayni kimlige sahiptir;
/// tekilleştirme (dedup) bu degere dayanir.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct MessageId([u8; MESSAGE_ID_LEN]);

impl MessageId {
    /// Ham baytlardan olusturur.
    pub const fn new(bytes: [u8; MESSAGE_ID_LEN]) -> Self {
        Self(bytes)
    }

    /// Ham baytlar.
    pub const fn as_bytes(&self) -> &[u8; MESSAGE_ID_LEN] {
        &self.0
    }

    /// Parcalama icin kullanilan 8 baytlik kisa etiket (kimligin ilk 8 bayti).
    pub fn transfer_tag(&self) -> [u8; 8] {
        let mut t = [0u8; 8];
        t.copy_from_slice(&self.0[..8]);
        t
    }
}

impl From<[u8; MESSAGE_ID_LEN]> for MessageId {
    fn from(b: [u8; MESSAGE_ID_LEN]) -> Self {
        Self(b)
    }
}

impl fmt::Display for MessageId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write_hex(&self.0, f)
    }
}

impl fmt::Debug for MessageId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "MessageId(")?;
        write_hex(&self.0[..4], f)?;
        write!(f, "..)")
    }
}
