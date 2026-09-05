"""Fail-closed source/APK/provenance checks. Never prints signing secrets."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def require(value, message):
    if not value:
        raise SystemExit(message)


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def baseline():
    return json.loads(Path("release/baseline.json").read_text())


def expected_source():
    sha = os.environ.get("EXPECTED_COMMIT", "")
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "Expected a full immutable commit SHA")
    require(run("git", "rev-parse", "HEAD").strip() == sha, "Checkout differs from expected commit")
    require(os.environ.get("GITHUB_SHA", sha) == sha, "Dispatch workflow on the exact candidate ref")
    return sha


def check_source():
    expected_source()
    b = baseline()
    gradle = Path("app/build.gradle.kts").read_text()
    require(re.search(r'applicationId\s*=\s*"' + re.escape(b["application_id"]) + '"', gradle), "Package mismatch")
    require(re.search(r'versionCode\s*=\s*' + str(b["version_code"]) + r'\b', gradle), "versionCode mismatch")
    require(re.search(r'versionName\s*=\s*"' + re.escape(b["version_name"]) + '"', gradle), "versionName mismatch")
    require(b["application_id"] == "excp.rikkahub" and b["prerelease"] is True, "Invalid release identity")
    require(b["tag"] == "v" + b["version_name"] and "alpha" in b["tag"], "Expected alpha tag")
    previous = int(os.environ.get("LAST_RELEASE_VERSION_CODE", "183") or "183")
    require(b["version_code"] > previous, "versionCode must increase over the previous release")
    require(not run("git", "status", "--porcelain", "--", "app/schemas").strip(), "Uncommitted Room schema")
    require(not run("git", "diff", "--name-only", "HEAD").strip(), "Tracked source differs from the expected commit")


def verify_apks(source_dir, destination):
    check_source()
    sha = expected_source()
    b = baseline()
    expected_cert = os.environ.get("RELEASE_CERT_SHA256", "").replace(":", "").lower()
    require(re.fullmatch(r"[0-9a-f]{64}", expected_cert), "SIGNING BLOCKER: trusted release certificate fingerprint is required")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "37.0.0"
    apk_paths = sorted(Path(source_dir).glob("*.apk"))
    require(len(apk_paths) == 3, "Expected arm64, x86_64 and universal release APKs")
    dest = Path(destination)
    dest.mkdir(parents=True, exist_ok=True)
    artifacts = []
    seen = set()
    for apk in apk_paths:
        require("release" in apk.name and "debug" not in apk.name and "benchmark" not in apk.name, "Wrong build variant")
        badging = run(str(tools / "aapt"), "dump", "badging", str(apk))
        package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        require(package and package.groups() == (b["application_id"], str(b["version_code"]), b["version_name"]), "APK package/version mismatch")
        require("application-debuggable" not in badging, "Debuggable APK cannot be released")
        abi_line = re.search(r"^native-code: (.+)$", badging, re.M)
        require(abi_line, "Missing native ABI information")
        abis = sorted(re.findall(r"'([^']+)'", abi_line.group(1)))
        abi = {("arm64-v8a",): "arm64", ("x86_64",): "x86_64", ("arm64-v8a", "x86_64"): "universal"}.get(tuple(abis))
        require(abi and abi not in seen, "Unexpected or duplicate ABI output")
        seen.add(abi)
        signing = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
        certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signing)
        require(len(certs) == 1 and certs[0].lower() == expected_cert, "APK signing identity mismatch")
        require(re.search(r"Verified using v2 scheme .*: true", signing), "APK v2 signature required")
        name = f"rikkahub-agent-{b['tag']}-{abi}-release.apk"
        output = dest / name
        if apk.resolve() != output.resolve():
            shutil.copyfile(apk, output)
        artifacts.append({"name": name, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(), "bytes": output.stat().st_size, "abis": abis})
    manifest = {"source_sha": sha, "source_tree": run("git", "rev-parse", "HEAD^{tree}").strip(), "version_code": b["version_code"], "version_name": b["version_name"], "application_id": b["application_id"], "certificate_sha256": expected_cert, "prerelease": True, "artifacts": artifacts}
    (dest / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (dest / "SHA256SUMS").write_text("".join(f"{a['sha256']}  {a['name']}\n" for a in artifacts))
    print("Verified three signed, non-debuggable release APKs and source provenance")


def verify_device(source_dir):
    p = json.loads((Path(source_dir) / "provenance.json").read_text())
    require(p["source_sha"] == expected_source(), "Build source differs from publish candidate")
    require(os.environ.get("DEVICE_VALIDATED") == "true", "NEEDS DEVICE VALIDATION")
    device_hash = os.environ.get("DEVICE_APK_SHA256", "").lower()
    arm64 = next(a for a in p["artifacts"] if a["abis"] == ["arm64-v8a"])
    require(device_hash == arm64["sha256"], "Device-tested APK differs from signed candidate")
    for item in p["artifacts"]:
        name = item["name"]
        require(Path(name).name == name, "Invalid artifact path")
        require(hashlib.sha256((Path(source_dir) / name).read_bytes()).hexdigest() == item["sha256"], "Artifact checksum mismatch")


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "source":
        check_source()
    elif mode == "apk":
        verify_apks(sys.argv[2], sys.argv[3])
    elif mode == "device":
        verify_device(sys.argv[2])
    else:
        raise SystemExit("Unknown mode")
