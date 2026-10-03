//! Golden vectors: the same inputs were produced by an independent Python
//! reference implementation (the `cryptography` library). If a single byte
//! differs, the wire format or the KDF context has been broken; investigate the
//! cause instead of editing the vector.

use crypto::seal::seal_with_ephemeral;
use crypto::{ContactCard, Identity, verify_envelope};
use proto::{Envelope, EnvelopeBody, Kind};

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap())
        .collect()
}

fn arr<const N: usize>(s: &str) -> [u8; N] {
    unhex(s).try_into().unwrap()
}

const SEED_A: &str = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
const CREATED_AT_MS: u64 = 1_700_000_000_000;

const A_NODE: &str = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
const A_XPUB: &str = "81ce6538d488d501e6e261248feb9b132199431e3dca032388d45d63e5673577";
const B_NODE: &str = "2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12";
const B_XPUB: &str = "659bd754c3420c12acb48c4a3a003b3f35e769d3f3fe18aad2cb21cf7208fe48";
const CARD_B: &str = "2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12659bd754c3420c12acb48c4a3a003b3f35e769d3f3fe18aad2cb21cf7208fe48c09b32721df6ed50d1d45cf752d491d631bbfad48fdd617184c452fe6e23b58f1a385dcc28b597b8e328ea03d59cdfc789e08bb233fa05fc3f70f000c9775d0b";
const FINGERPRINT_B_FIRST15: &str = "d794da3ee9d4925189177999ea6c23";
const SEALED: &str = "13be4feaeaf204c7fd3358fc9c00721881d174278128227ec674f37f7fe97b6dcdc17b40b1bdddd23e0af155182234b2cb7a0a27637abe17e78ae315";
const DIRECT_ENV: &str = "010200d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db120000018bcfe5680000000e1000003c13be4feaeaf204c7fd3358fc9c00721881d174278128227ec674f37f7fe97b6dcdc17b40b1bdddd23e0af155182234b2cb7a0a27637abe17e78ae3159d5ea4bd8549d7428214a74c338d1778871f5ada0280a776571a0c21c882fb625126ac1ca7f937feb1f6a0adc860e8832e29f61a21eb79cf729bb4a324134204";
const DIRECT_SIG: &str = "9d5ea4bd8549d7428214a74c338d1778871f5ada0280a776571a0c21c882fb625126ac1ca7f937feb1f6a0adc860e8832e29f61a21eb79cf729bb4a324134204";
const DIRECT_ID: &str = "23fc4e01d35b28daed547be6810cc7df87aeec8190ded34a35e89f51c3ebe15b";
const BCAST_ENV: &str = "010100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a0000018bcfe568000000025800000568656c6c6fb663b36104e934a0d4a337a2391f9714755c16499588c1bc94b9a46f1a792477b2be68fc1c1dbfedf698d022ee7a29ba5da05c8e8cb29e113e682f2e160caa04";
const BCAST_ID: &str = "70755162d40ae89ae40b7518978e9430416095b6da937d14de8e8eb50bfa98bb";

fn ids() -> (Identity, Identity) {
    (
        Identity::from_seed(&arr::<32>(SEED_A)).unwrap(),
        Identity::from_seed(&[0x42; 32]).unwrap(),
    )
}

#[test]
fn identities_and_contact_card() {
    let (a, b) = ids();
    assert_eq!(*a.node_id().as_bytes(), arr::<32>(A_NODE));
    assert_eq!(a.exchange_public(), arr::<32>(A_XPUB));
    assert_eq!(*b.node_id().as_bytes(), arr::<32>(B_NODE));
    assert_eq!(b.exchange_public(), arr::<32>(B_XPUB));

    let card = b.contact_card();
    assert_eq!(card.to_bytes().to_vec(), unhex(CARD_B));
    assert_eq!(card.fingerprint()[..15], unhex(FINGERPRINT_B_FIRST15)[..]);
    assert_eq!(ContactCard::from_bytes(&unhex(CARD_B)).unwrap(), card);
}

#[test]
fn direct_message_matches_reference() {
    let (a, b) = ids();
    let card_b = b.contact_card();

    let sealed = seal_with_ephemeral(&a.node_id(), &card_b, b"merhaba mesh", &[0x07; 32]).unwrap();
    assert_eq!(sealed, unhex(SEALED));

    let body = EnvelopeBody {
        kind: Kind::Direct,
        sender: a.node_id(),
        recipient: Some(b.node_id()),
        created_at_ms: CREATED_AT_MS,
        lifetime_secs: 3600,
        ext: vec![],
        payload: sealed,
    };
    let env = a.sign_body(body).unwrap();
    assert_eq!(env.signature.to_vec(), unhex(DIRECT_SIG));
    assert_eq!(env.encode().unwrap(), unhex(DIRECT_ENV));
    assert_eq!(
        env.message_id().unwrap().as_bytes().to_vec(),
        unhex(DIRECT_ID)
    );

    // Recipient side: decode from bytes, verify, open.
    let decoded = Envelope::decode(&unhex(DIRECT_ENV)).unwrap();
    verify_envelope(&decoded).unwrap();
    assert_eq!(b.open_direct(&decoded).unwrap().as_slice(), b"merhaba mesh");
    // The sender cannot open its own message.
    assert!(a.open_direct(&decoded).is_err());
}

#[test]
fn broadcast_matches_reference() {
    let (a, _) = ids();
    let env = a.broadcast(b"hello", CREATED_AT_MS, 600).unwrap();
    assert_eq!(env.encode().unwrap(), unhex(BCAST_ENV));
    assert_eq!(
        env.message_id().unwrap().as_bytes().to_vec(),
        unhex(BCAST_ID)
    );
    verify_envelope(&Envelope::decode(&unhex(BCAST_ENV)).unwrap()).unwrap();
}
