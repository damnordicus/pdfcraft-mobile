//! Embeds the git commit this crate was built from, so `build_info()` can prove to a device (or
//! whoever's debugging a "did my rebuild actually pick this up" question) which source it's
//! running, not just that *a* build succeeded.

use std::process::Command;

fn main() {
    let sha = Command::new("git")
        .args(["rev-parse", "--short", "HEAD"])
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .map(|sha| sha.trim().to_owned())
        .unwrap_or_else(|| "unknown".to_owned());
    println!("cargo:rustc-env=PDFCRAFT_MOBILE_FFI_COMMIT={sha}");
}
