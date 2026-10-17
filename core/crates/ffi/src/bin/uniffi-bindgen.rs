//! The UniFFI binding generator, built from the same UniFFI version as the library.
//!
//! Run through `scripts/gen-bindings.sh`.

fn main() {
    uniffi::uniffi_bindgen_main();
}
