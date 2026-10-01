#!/usr/bin/env python3
"""Secret scanner for the repository and for built artifacts (APK/AAB are zip files).

Usage:
    python scripts/secret_scan.py                       # scan the repository
    python scripts/secret_scan.py app/build/outputs/... # also scan APK/AAB files or directories

Exit code 1 if anything that looks like a real secret is found. Test fixtures that are clearly
labelled as non-secret (see ALLOW) are ignored.
"""
import os
import re
import sys
import zipfile

PATTERNS = {
    "private key block": re.compile(rb"-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
    "Razorpay live key": re.compile(rb"rzp_live_[A-Za-z0-9]{10,}"),
    "Stripe secret key": re.compile(rb"sk_live_[A-Za-z0-9]{10,}"),
    "AWS access key": re.compile(rb"AKIA[0-9A-Z]{16}"),
    "Google API key": re.compile(rb"AIza[0-9A-Za-z_\-]{35}"),
    "GitHub token": re.compile(rb"gh[pousr]_[A-Za-z0-9]{36,}"),
    "Slack token": re.compile(rb"xox[baprs]-[A-Za-z0-9-]{10,}"),
    "prototype HMAC secret": re.compile(rb"paylift_escrow_sec_key"),
    "hardcoded secret assignment": re.compile(
        rb"(?i)(secret|password|passwd|api[_-]?key|private[_-]?key|webhook[_-]?secret|jwt[_-]?secret)"
        rb"\s*[:=]\s*[\"']([A-Za-z0-9+/_\-]{24,})[\"']"
    ),
    "keystore file": None,  # handled by extension check
}
KEYSTORE_EXT = (".jks", ".keystore", ".p12", ".pem", ".pfx")
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", ".idea", ".kotlin", "data", "screenshots", "ui-baseline-original"}
TEXT_EXT = (".kt", ".kts", ".java", ".js", ".json", ".xml", ".properties", ".toml", ".yml", ".yaml", ".md",
            ".py", ".sh", ".txt", ".env", ".example", ".gradle", ".pro", ".sql", ".cfg")
# Values that are deliberately fake and labelled as such.
ALLOW = [b"test-admin-token-not-a-secret", b"test_key_public"]


def scan_bytes(name, data, findings):
    for label, rx in PATTERNS.items():
        if rx is None:
            continue
        for m in rx.finditer(data):
            snippet = m.group(0)
            if any(a in snippet for a in ALLOW):
                continue
            findings.append(f"{name}: {label}: {snippet[:60]!r}")


def scan_zip(path, findings):
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.file_size > 50_000_000:
                continue
            scan_bytes(f"{path}!{info.filename}", z.read(info.filename), findings)


def scan_tree(root, findings):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            p = os.path.join(dirpath, f)
            if f.endswith(KEYSTORE_EXT):
                findings.append(f"{p}: keystore/certificate file committed")
                continue
            if f == ".env" or (f.startswith(".env.") and f not in (".env.example", ".env.test")):
                findings.append(f"{p}: local env file present in tree (must be git-ignored, never committed)")
            if f.endswith(TEXT_EXT) or f.startswith(".env"):
                if p.endswith(os.path.join("scripts", "secret_scan.py")):
                    continue
                with open(p, "rb") as fh:
                    scan_bytes(p, fh.read(), findings)


def main(argv):
    findings = []
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    scan_tree(repo, findings)
    for target in argv[1:]:
        if os.path.isdir(target):
            for dirpath, _, filenames in os.walk(target):
                for f in filenames:
                    if f.endswith((".apk", ".aab")):
                        scan_zip(os.path.join(dirpath, f), findings)
        elif target.endswith((".apk", ".aab")):
            scan_zip(target, findings)
    if findings:
        print("Potential secrets found:")
        for f in findings:
            print("  " + f)
        return 1
    print("Secret scan clean (repository" + (" + artifacts" if len(argv) > 1 else "") + ").")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
