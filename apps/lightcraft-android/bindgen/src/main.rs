//! `uniffi-bindgen` for `lightcraft-android`: generates its bindings from the built library, e.g.
//! `cargo run -p lightcraft-android-bindgen -- generate --library liblightcraft_android.so --language kotlin --out-dir out`
//! (`android/app/build.gradle.kts` runs it).
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

fn main() {
    uniffi::uniffi_bindgen_main()
}
