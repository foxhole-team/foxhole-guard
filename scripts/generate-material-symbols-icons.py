#!/usr/bin/env python3
"""Generate the vendored Material Symbols icon pack under app/.../ui/icons/.

The androidx material-icons-extended artifact is deprecated (frozen since 2025), so the app
vendors the Material Symbols glyphs it actually uses instead of pulling a third-party port
(dependency-verification keeps the dependency tree closed). This script downloads the SVG
sources from google/material-design-icons (Apache-2.0), converts each path to a Compose
ImageVector builder, and writes one Kotlin file per glyph plus the MaterialSymbols catalog
object. Property names keep the legacy Material Icons spelling so call sites stay greppable
against the old androidx set.

Usage:
    scripts/generate-material-symbols-icons.py [--svg-cache DIR]

With --svg-cache, SVGs already present in DIR are reused and missing ones are downloaded
into it; without it a throwaway cache under the system temp dir is used.

SUPERSEDED on this branch: ui/icons/ now holds the hand-drawn 16-bit pixel pack generated
by ex/tools/gen_icons_kotlin.py from ex/design/icons/grids/*.txt. This script wipes every
.kt in TARGET_DIR before regenerating, so running it would silently destroy that pack.
It is kept for provenance (and in case the pixel pass is reverted) but now refuses to run
without --force-replace-pixel-pack.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TARGET_DIR = REPO_ROOT / "app/src/main/kotlin/com/foxhole/guard/ui/icons"
RAW_BASE = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web"

OUTLINED = "materialsymbolsoutlined"
ROUNDED = "materialsymbolsrounded"

# (kotlin name, Material Symbols glyph name, family directory, fill1 variant)
ICONS = [
    ("AccountTree", "account_tree", OUTLINED, False),
    ("Add", "add", OUTLINED, False),
    ("Api", "api", OUTLINED, False),
    ("AppBlocking", "app_blocking", OUTLINED, False),
    ("Apps", "apps", OUTLINED, False),
    ("ArrowBack", "arrow_back", OUTLINED, False),
    ("ArrowDownward", "arrow_downward", OUTLINED, False),
    ("ArrowOutward", "arrow_outward", OUTLINED, False),
    ("ArrowUpward", "arrow_upward", OUTLINED, False),
    ("Article", "article", OUTLINED, False),
    ("AutoMode", "auto_mode", OUTLINED, False),
    ("Backspace", "backspace", OUTLINED, False),
    ("BarChart", "bar_chart", OUTLINED, False),
    ("BatterySaver", "battery_saver", OUTLINED, False),
    ("Bedtime", "bedtime", OUTLINED, False),
    ("Block", "block", OUTLINED, False),
    ("BlurOn", "blur_on", OUTLINED, False),
    ("BrightnessAuto", "brightness_auto", OUTLINED, False),
    ("BugReport", "bug_report", OUTLINED, False),
    ("Business", "domain", OUTLINED, False),
    ("CallSplit", "call_split", OUTLINED, False),
    ("Campaign", "campaign", OUTLINED, False),
    ("CellTower", "cell_tower", OUTLINED, False),
    ("Check", "check", OUTLINED, False),
    ("CheckCircle", "check_circle", OUTLINED, False),
    ("CheckCircleFilled", "check_circle", OUTLINED, True),
    ("CheckRounded", "check", ROUNDED, False),
    ("ChevronRight", "chevron_right", OUTLINED, False),
    ("Close", "close", OUTLINED, False),
    ("CloudOff", "cloud_off", OUTLINED, False),
    ("Code", "code", OUTLINED, False),
    ("CompareArrows", "compare_arrows", OUTLINED, False),
    ("ContentCopy", "content_copy", OUTLINED, False),
    ("ContentPaste", "content_paste", OUTLINED, False),
    ("DarkMode", "dark_mode", OUTLINED, False),
    ("Dashboard", "dashboard", OUTLINED, False),
    ("DataSaverOn", "data_saver_on", OUTLINED, False),
    ("DataUsage", "data_usage", OUTLINED, False),
    ("Delete", "delete", OUTLINED, False),
    ("DeleteForever", "delete_forever", OUTLINED, False),
    ("DeleteSweep", "delete_sweep", OUTLINED, False),
    ("Description", "description", OUTLINED, False),
    ("DeveloperMode", "developer_mode", OUTLINED, False),
    ("Dns", "dns", OUTLINED, False),
    ("Download", "download", OUTLINED, False),
    ("DragIndicatorRounded", "drag_indicator", ROUNDED, False),
    ("Edit", "edit", OUTLINED, False),
    ("Equalizer", "equalizer", OUTLINED, False),
    ("ErrorOutline", "error", OUTLINED, False),
    ("ExpandLess", "expand_less", OUTLINED, False),
    ("ExpandMore", "expand_more", OUTLINED, False),
    ("FileUpload", "upload", OUTLINED, False),
    ("FilterAlt", "filter_alt", OUTLINED, False),
    ("Fingerprint", "fingerprint", OUTLINED, False),
    ("FolderOpen", "folder_open", OUTLINED, False),
    ("GppBad", "gpp_bad", OUTLINED, False),
    ("GridView", "grid_view", OUTLINED, False),
    ("HelpOutline", "help", OUTLINED, False),
    ("History", "history", OUTLINED, False),
    ("Hub", "hub", OUTLINED, False),
    ("Info", "info", OUTLINED, False),
    ("Insights", "insights", OUTLINED, False),
    ("Key", "key", OUTLINED, False),
    ("KeyboardArrowDown", "keyboard_arrow_down", OUTLINED, False),
    ("KeyboardArrowUp", "keyboard_arrow_up", OUTLINED, False),
    ("Lan", "lan", OUTLINED, False),
    ("Language", "language", OUTLINED, False),
    ("Layers", "layers", OUTLINED, False),
    ("LightMode", "light_mode", OUTLINED, False),
    ("Link", "link", OUTLINED, False),
    ("LocationCity", "location_city", OUTLINED, False),
    ("Lock", "lock", OUTLINED, False),
    ("Map", "map", OUTLINED, False),
    ("NoEncryption", "no_encryption", OUTLINED, False),
    ("NoPhotography", "no_photography", OUTLINED, False),
    ("Notifications", "notifications", OUTLINED, False),
    ("NotificationsActive", "notifications_active", OUTLINED, False),
    ("Numbers", "numbers", OUTLINED, False),
    ("OpenInNew", "open_in_new", OUTLINED, False),
    ("Palette", "palette", OUTLINED, False),
    ("Password", "password", OUTLINED, False),
    ("Person", "person", OUTLINED, False),
    ("PhoneAndroid", "phone_android", OUTLINED, False),
    ("PieChart", "pie_chart", OUTLINED, False),
    ("PlayArrow", "play_arrow", OUTLINED, False),
    ("PowerSettingsNew", "power_settings_new", OUTLINED, False),
    ("PrivacyTip", "privacy_tip", OUTLINED, False),
    ("Public", "public", OUTLINED, False),
    ("QrCodeScanner", "qr_code_scanner", OUTLINED, False),
    ("QueryStats", "query_stats", OUTLINED, False),
    ("Radar", "radar", OUTLINED, False),
    ("RadioButtonUnchecked", "radio_button_unchecked", OUTLINED, False),
    ("Refresh", "refresh", OUTLINED, False),
    ("RemoveCircleOutline", "do_not_disturb_on", OUTLINED, False),
    ("RestartAlt", "restart_alt", OUTLINED, False),
    ("RocketLaunch", "rocket_launch", OUTLINED, False),
    ("Route", "route", OUTLINED, False),
    ("Router", "router", OUTLINED, False),
    ("Save", "save", OUTLINED, False),
    ("Schedule", "schedule", OUTLINED, False),
    ("Science", "science", OUTLINED, False),
    ("Search", "search", OUTLINED, False),
    ("Security", "security", OUTLINED, False),
    ("Settings", "settings", OUTLINED, False),
    ("SettingsApplications", "settings_applications", OUTLINED, False),
    ("SettingsBackupRestore", "settings_backup_restore", OUTLINED, False),
    ("SettingsEthernet", "settings_ethernet", OUTLINED, False),
    ("Shield", "shield", OUTLINED, False),
    ("ShowChart", "show_chart", OUTLINED, False),
    ("SignalCellularAlt", "signal_cellular_alt", OUTLINED, False),
    ("Smartphone", "smartphone", OUTLINED, False),
    ("Speed", "speed", OUTLINED, False),
    ("Star", "star", OUTLINED, False),
    ("Stop", "stop", OUTLINED, False),
    ("Storage", "storage", OUTLINED, False),
    ("Straighten", "straighten", OUTLINED, False),
    ("SwapHoriz", "swap_horiz", OUTLINED, False),
    ("SwapVert", "swap_vert", OUTLINED, False),
    ("SwipeLeft", "swipe_left", OUTLINED, False),
    ("SwipeRight", "swipe_right", OUTLINED, False),
    ("SwipeUp", "swipe_up", OUTLINED, False),
    ("Sync", "sync", OUTLINED, False),
    ("TableChart", "table_chart", OUTLINED, False),
    ("TouchApp", "touch_app", OUTLINED, False),
    ("Translate", "translate", OUTLINED, False),
    ("Troubleshoot", "troubleshoot", OUTLINED, False),
    ("Tune", "tune", OUTLINED, False),
    ("Visibility", "visibility", OUTLINED, False),
    ("VisibilityOff", "visibility_off", OUTLINED, False),
    ("VpnKey", "vpn_key", OUTLINED, False),
    ("VpnLock", "vpn_lock", OUTLINED, False),
    ("WarningAmber", "warning", OUTLINED, False),
    ("Widgets", "widgets", OUTLINED, False),
    ("Wifi", "wifi", OUTLINED, False),
]

# RTL-mirrored glyphs (the androidx set had these under Icons.AutoMirrored).
MIRRORED = {
    "ArrowBack",
    "Article",
    "Backspace",
    "CallSplit",
    "CompareArrows",
    "HelpOutline",
    "OpenInNew",
    "ShowChart",
}

# Legacy names whose Material Symbols glyph is identical to another entry: catalog aliases,
# no duplicated path data.
ALIASES = {
    "AccessTime": "Schedule",
    "DeleteOutline": "Delete",
    "FileDownload": "Download",
}

NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
COMMAND_PARAMS = {
    "M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0,
}
BUILDER_CALLS = {
    "M": ("moveTo", "moveToRelative"),
    "L": ("lineTo", "lineToRelative"),
    "H": ("horizontalLineTo", "horizontalLineToRelative"),
    "V": ("verticalLineTo", "verticalLineToRelative"),
    "C": ("curveTo", "curveToRelative"),
    "S": ("reflectiveCurveTo", "reflectiveCurveToRelative"),
    "Q": ("quadTo", "quadToRelative"),
    "T": ("reflectiveQuadTo", "reflectiveQuadToRelative"),
}

GENERATED_HEADER = "// GENERATED by scripts/generate-material-symbols-icons.py — do not edit by hand."


def fetch_svg(cache: Path, kotlin_name: str, glyph: str, family: str, fill1: bool) -> str:
    cached = cache / f"{kotlin_name}.svg"
    if not cached.is_file():
        suffix = "_fill1_24px.svg" if fill1 else "_24px.svg"
        url = f"{RAW_BASE}/{glyph}/{family}/{glyph}{suffix}"
        result = subprocess.run(["curl", "-sf", url, "-o", str(cached)], check=False)
        if result.returncode != 0:
            sys.exit(f"download failed: {url}")
    return cached.read_text()


def parse_svg(svg: str) -> tuple[float, float, float, list[str]]:
    """Return (viewport_w, viewport_h, y_offset, path_data_list)."""
    view_box = re.search(r'viewBox="([^"]+)"', svg)
    if view_box:
        min_x, min_y, width, height = (float(v) for v in view_box.group(1).split())
        if min_x != 0:
            sys.exit(f"unsupported viewBox min-x: {view_box.group(1)}")
        y_offset = -min_y
    else:
        width = float(re.search(r'width="([\d.]+)"', svg).group(1))
        height = float(re.search(r'height="([\d.]+)"', svg).group(1))
        y_offset = 0.0
    paths = re.findall(r'<path[^>]*\bd="([^"]+)"', svg)
    if not paths:
        sys.exit("no <path d=...> found")
    return width, height, y_offset, paths


def fmt(value: float) -> str:
    if value == int(value):
        return f"{int(value)}f"
    text = f"{value:.6f}".rstrip("0").rstrip(".")
    return f"{text}f"


def path_commands(data: str) -> list[str]:
    """Translate one SVG path-data string into PathBuilder call lines."""
    tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|" + NUMBER.pattern, data)
    lines: list[str] = []
    index = 0

    def take_numbers(count: int) -> list[float]:
        nonlocal index
        values = [float(t) for t in tokens[index : index + count]]
        if len(values) != count:
            sys.exit(f"truncated path data: {data[:60]}...")
        index += count
        return values

    command = None
    while index < len(tokens):
        token = tokens[index]
        if token.isalpha() and len(token) == 1:
            command = token
            index += 1
        elif command is None:
            sys.exit(f"path data does not start with a command: {data[:60]}...")
        elif command in "Mm":
            command = "L" if command == "M" else "l"  # implicit lineto after moveto
        upper = command.upper()
        relative = command.islower()
        if upper == "Z":
            lines.append("close()")
            continue
        params = take_numbers(COMMAND_PARAMS[upper])
        if upper == "A":
            rx, ry, rot, large, sweep, x, y = params
            call = "arcToRelative" if relative else "arcTo"
            lines.append(
                f"{call}({fmt(rx)}, {fmt(ry)}, {fmt(rot)}, "
                f"{str(large != 0).lower()}, {str(sweep != 0).lower()}, {fmt(x)}, {fmt(y)})"
            )
        else:
            call = BUILDER_CALLS[upper][1 if relative else 0]
            lines.append(f"{call}({', '.join(fmt(v) for v in params)})")
    return lines


def glyph_file(kotlin_name: str, glyph: str, family: str, fill1: bool, svg: str) -> str:
    width, height, y_offset, paths = parse_svg(svg)
    mirrored = kotlin_name in MIRRORED
    grouped = y_offset != 0
    family_label = "rounded" if family == ROUNDED else "outlined"
    variant = ", fill 1" if fill1 else ""

    body: list[str] = []
    pad = " " * (16 if grouped else 12)
    if grouped:
        body.append(f"        group(translationY = {fmt(y_offset)}) {{")
    for data in paths:
        body.append(f"{pad[:-4]}path(fill = SolidColor(Color.Black)) {{")
        body.extend(f"{pad}{line}" for line in path_commands(data))
        body.append(f"{pad[:-4]}}}")
    if grouped:
        body.append("        }")

    imports = [
        "androidx.compose.ui.graphics.Color",
        "androidx.compose.ui.graphics.SolidColor",
        "androidx.compose.ui.graphics.vector.ImageVector",
        "androidx.compose.ui.graphics.vector.group" if grouped else None,
        "androidx.compose.ui.graphics.vector.path",
        "androidx.compose.ui.unit.dp",
    ]
    mirror_line = "        autoMirror = true,\n" if mirrored else ""
    import_block = "\n".join(f"import {i}" for i in imports if i)
    return (
        f"{GENERATED_HEADER}\n"
        f'// Material Symbols "{glyph}" ({family_label}{variant}), google/material-design-icons,\n'
        "// Apache License 2.0.\n"
        '@file:Suppress("LongMethod")\n'
        "\n"
        "package com.foxhole.guard.ui.icons\n"
        "\n"
        f"{import_block}\n"
        "\n"
        f"internal fun materialSymbol{kotlin_name}(): ImageVector =\n"
        "    ImageVector.Builder(\n"
        f'        name = "MaterialSymbols.{kotlin_name}",\n'
        "        defaultWidth = 24.dp,\n"
        "        defaultHeight = 24.dp,\n"
        f"        viewportWidth = {fmt(width)},\n"
        f"        viewportHeight = {fmt(height)},\n"
        f"{mirror_line}"
        "    ).apply {\n"
        + "\n".join(body)
        + "\n"
        "    }.build()\n"
    )


def catalog_file() -> str:
    entries = {name: f"val {name}: ImageVector by lazy {{ materialSymbol{name}() }}" for name, _, _, _ in ICONS}
    entries.update(
        (alias, f"val {alias}: ImageVector get() = {target}") for alias, target in ALIASES.items()
    )
    lines = "\n".join(f"    {entries[name]}" for name in sorted(entries))
    return (
        f"{GENERATED_HEADER}\n"
        "\n"
        "package com.foxhole.guard.ui.icons\n"
        "\n"
        "import androidx.compose.ui.graphics.vector.ImageVector\n"
        "\n"
        "/**\n"
        " * Vendored Material Symbols glyphs (google/material-design-icons, Apache-2.0) replacing the\n"
        " * deprecated androidx material-icons-extended artifact. Property names keep the legacy\n"
        " * Material Icons spelling so call sites stay greppable against the old androidx set.\n"
        " */\n"
        "internal object MaterialSymbols {\n"
        f"{lines}\n"
        "}\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--svg-cache", type=Path, default=None)
    parser.add_argument(
        "--force-replace-pixel-pack",
        action="store_true",
        help="Wipe the 16-bit pixel icon pack in ui/icons/ and restore the Material Symbols set.",
    )
    args = parser.parse_args()

    if not args.force_replace_pixel_pack:
        sys.exit(
            "refusing to run: ui/icons/ holds the 16-bit pixel pack (source: "
            "ex/design/icons/grids/, regenerate with ex/tools/gen_icons_kotlin.py).\n"
            "This script deletes every .kt there first. Pass --force-replace-pixel-pack "
            "only if you really mean to revert to the Material Symbols set."
        )

    cache = args.svg_cache or Path(tempfile.mkdtemp(prefix="material-symbols-svg-"))
    cache.mkdir(parents=True, exist_ok=True)

    TARGET_DIR.mkdir(parents=True, exist_ok=True)
    for stale in TARGET_DIR.glob("*.kt"):
        stale.unlink()

    for kotlin_name, glyph, family, fill1 in ICONS:
        svg = fetch_svg(cache, kotlin_name, glyph, family, fill1)
        (TARGET_DIR / f"{kotlin_name}.kt").write_text(glyph_file(kotlin_name, glyph, family, fill1, svg))
    (TARGET_DIR / "MaterialSymbols.kt").write_text(catalog_file())
    print(f"generated {len(ICONS)} glyph files + MaterialSymbols.kt in {TARGET_DIR}")


if __name__ == "__main__":
    main()
