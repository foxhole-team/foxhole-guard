"""Trusted app ABI choice, independent of native/SBOM receipt declarations."""
import hashlib
import json
from pathlib import Path

PROFILES = {
    "android4": (["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
                 ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"]),
    "legacy4": (["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
                ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"]),
    "android64": (["arm64-v8a", "x86_64"], ["aarch64-linux-android", "x86_64-linux-android"]),
    "android-armv7": (["armeabi-v7a"], ["armv7-linux-androideabi"]),
    "android3": (["arm64-v8a", "armeabi-v7a", "x86_64"], ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"]),
}

def require(value, reason):
    if not value: raise ValueError(reason)

def profile_shape(profile):
    require(isinstance(profile, str) and profile in PROFILES, "Unknown Android delivery profile")
    abis, targets = PROFILES[profile]
    return {"androidDeliveryProfile": profile, "abis": list(abis), "targets": list(targets)}

def read_profile(root, required=False):
    path = Path(root) / "config/foxcore-android-delivery.json"
    if not path.exists() and not path.is_symlink():
        require(not required, "Trusted Android delivery configuration is required")
        return "legacy4", None
    require(not path.is_symlink() and path.is_file() and path.stat().st_size <= 8192, "Invalid trusted Android delivery configuration")
    raw = path.read_bytes()
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, "Duplicate Android delivery configuration key")
            result[key] = value
        return result
    record = json.loads(raw, object_pairs_hook=pairs)
    expected = {"schema": 1, **profile_shape(record.get("androidDeliveryProfile"))}
    require(record == expected and type(record.get("schema")) is int, "Android delivery configuration differs from exact profile")
    return record["androidDeliveryProfile"], hashlib.sha256(raw).hexdigest()

def require_fields(record, profile):
    expected = profile_shape(profile)
    require(all(record.get(k) == value for k, value in expected.items()), "Android delivery source/notice profile differs from trusted configuration")


def generator_profile(args, root):
    requested = getattr(args, "core_delivery_profile", None)
    abis = getattr(args, "android_abis", None)
    supplied = abis.replace(",", " ").split() if isinstance(abis, str) else None
    require(supplied is None or len(supplied) == len(set(supplied)), "Duplicate selected Android ABI")
    exact64 = supplied is not None and set(supplied) == set(PROFILES["android64"][0])
    profile, _ = read_profile(root, required=requested in ("android64", "android-armv7", "android3", "android4") or exact64 or supplied == ["armeabi-v7a"] or supplied == PROFILES["android3"][0])
    if requested is not None:
        require(requested == profile, "CLI Android profile differs from trusted app configuration")
    if profile != "legacy4":
        require(supplied == PROFILES[profile][0] or (profile == "android64" and exact64), "Selected profile requires its exact ABI set")
        pins = json.loads((Path(root) / "config/foxcore-source-manifest.json").read_bytes())
        require_fields(pins, profile)
    elif supplied is not None and getattr(args, "foxcore_sbom", None):
        require(set(supplied) == set(PROFILES["legacy4"][0]), "Selected legacy4 delivery requires all four ABIs")
    return profile
