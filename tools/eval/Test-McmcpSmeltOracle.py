#!/usr/bin/env python3
"""Pure path-resolution checks for Inspect-McmcpSmeltOracle.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import types


sys.modules.setdefault("nbtlib", types.ModuleType("nbtlib"))
path = Path(__file__).with_name("Inspect-McmcpSmeltOracle.py")
spec = importlib.util.spec_from_file_location("mcmcp_smelt_oracle", path)
if spec is None or spec.loader is None:
    raise SystemExit(f"cannot load oracle: {path}")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def rejected(root: Path) -> bool:
    try:
        oracle.resolve_overworld_dimension(root)
    except SystemExit:
        return True
    return False


with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)

    legacy = root / "legacy"
    (legacy / "region").mkdir(parents=True)
    assert oracle.resolve_overworld_dimension(legacy) == legacy

    modern = root / "modern"
    nested = modern / "dimensions" / "minecraft" / "overworld"
    (nested / "region").mkdir(parents=True)
    assert oracle.resolve_overworld_dimension(modern) == nested
    assert oracle.resolve_overworld_dimension(nested) == nested

    missing = root / "missing"
    missing.mkdir()
    assert rejected(missing)

    not_a_directory = root / "not-a-directory"
    not_a_directory.mkdir()
    (not_a_directory / "region").write_text("not a directory", encoding="utf-8")
    assert rejected(not_a_directory)

    ambiguous = root / "ambiguous"
    (ambiguous / "region").mkdir(parents=True)
    (ambiguous / "dimensions" / "minecraft" / "overworld" / "region").mkdir(
        parents=True
    )
    assert rejected(ambiguous)

print("MCMCP smelt oracle path-resolution tests passed.")
