#!/usr/bin/env python3
import datetime
import os
import re
import sys
from pathlib import Path


HOME = Path.home()
ETS2_HOME = HOME / "Library" / "Application Support" / "Euro Truck Simulator 2"
PROFILE = ETS2_HOME / "steam_profiles" / "616E6F6E696D7134"
GLOBAL_CONTROLS = ETS2_HOME / "global_controls.sii"
CONTROLS = PROFILE / "controls_osx.sii"


def find_s23_device_id():
    text = GLOBAL_CONTROLS.read_text(errors="replace")
    matches = re.findall(r"`(sys\.'hid-0075-0100-[^']+')`\|S23", text)
    if not matches:
        raise RuntimeError("S23 HID device was not found in global_controls.sii")
    return matches[-1]


def replace_line(text, index, body):
    pattern = re.compile(rf' config_lines\[{index}\]: ".*"')
    replacement = f' config_lines[{index}]: "{body}"'
    text, count = pattern.subn(replacement, text)
    if count != 1:
        raise RuntimeError(f"Expected exactly one config_lines[{index}] entry, found {count}")
    return text


def main():
    if not GLOBAL_CONTROLS.exists():
        raise RuntimeError(f"Missing {GLOBAL_CONTROLS}")
    if not CONTROLS.exists():
        raise RuntimeError(f"Missing {CONTROLS}")

    device_id = find_s23_device_id()
    text = CONTROLS.read_text(errors="replace")
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = CONTROLS.with_name(f"{CONTROLS.name}.backup-screenvr-bthid-{stamp}")
    backup.write_text(text)

    replacements = {
        4: f"device joy2 `{device_id}`",
        284: "mix trackiron `1`",
        285: "mix trackiryaw `joy2.x?0`",
        286: "mix trackirpitch `joy2.y?0`",
        287: "mix trackirroll `joy2.rx?0`",
        288: "mix trackirx `0`",
        289: "mix trackiry `0`",
        290: "mix trackirz `0`",
    }
    for index, body in replacements.items():
        text = replace_line(text, index, body)
    CONTROLS.write_text(text)
    print(f"Mapped S23 HID to ETS2 joy2: {device_id}")
    print(f"Backup: {backup}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
