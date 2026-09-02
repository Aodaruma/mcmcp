#!/usr/bin/env python3
"""Read an offline Minecraft Anvil region into deterministic block-state JSON.

The world must be closed (Save and Quit completed) before this external oracle is
run. It does not start Minecraft, call MCMCP, or modify the world.
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import math
import os
from pathlib import Path
import struct
import sys
import zlib


def _load_nbtlib():
    dependency_path = os.environ.get("MCMCP_PYDEPS")
    if dependency_path:
        sys.path.insert(0, dependency_path)
    sibling_dependencies = Path(__file__).with_name("pydeps")
    if sibling_dependencies.is_dir():
        sys.path.insert(0, str(sibling_dependencies))
    try:
        import nbtlib  # type: ignore
    except ImportError as failure:
        raise SystemExit(
            "nbtlib is required; install it into tools/eval/pydeps or set "
            "MCMCP_PYDEPS to an isolated dependency directory"
        ) from failure
    return nbtlib


NBTLIB = _load_nbtlib()


def load_chunk(region_path: Path, chunk_x: int, chunk_z: int):
    index = (chunk_x % 32) + (chunk_z % 32) * 32
    with region_path.open("rb") as stream:
        stream.seek(index * 4)
        location_bytes = stream.read(3)
        sector_bytes = stream.read(1)
        if len(location_bytes) != 3 or len(sector_bytes) != 1:
            raise SystemExit(f"truncated region header: {region_path}")
        location = int.from_bytes(location_bytes, "big")
        sectors = sector_bytes[0]
        if location == 0 or sectors == 0:
            return None
        stream.seek(location * 4096)
        length_bytes = stream.read(4)
        if len(length_bytes) != 4:
            raise SystemExit(f"truncated chunk header: {region_path}")
        length = struct.unpack(">I", length_bytes)[0]
        compression_raw = stream.read(1)
        if len(compression_raw) != 1 or length < 1:
            raise SystemExit(f"invalid chunk record: {region_path}")
        compression = compression_raw[0]
        payload = stream.read(length - 1)
        if len(payload) != length - 1:
            raise SystemExit(f"truncated chunk payload: {region_path}")
    if compression == 2:
        payload = zlib.decompress(payload)
    elif compression == 1:
        payload = gzip.decompress(payload)
    elif compression != 3:
        raise SystemExit(f"unsupported Anvil compression type {compression}")
    return NBTLIB.File.parse(io.BytesIO(payload))


def unpack(indices, bits: int, count: int = 4096) -> list[int]:
    if bits == 0:
        return [0] * count
    mask = (1 << bits) - 1
    values: list[int] = []
    per_long = 64 // bits
    for raw in indices:
        value = int(raw) & ((1 << 64) - 1)
        for offset in range(per_long):
            values.append((value >> (offset * bits)) & mask)
            if len(values) == count:
                return values
    return values + [0] * (count - len(values))


def inspect_region(world: Path, bounds: tuple[int, int, int, int, int, int]):
    min_x, max_x, min_y, max_y, min_z, max_z = bounds
    region_directory = world / "region"
    if not region_directory.is_dir():
        raise SystemExit(f"world region directory does not exist: {region_directory}")
    result = []
    for chunk_x in range(math.floor(min_x / 16), math.floor(max_x / 16) + 1):
        for chunk_z in range(math.floor(min_z / 16), math.floor(max_z / 16) + 1):
            region_x = math.floor(chunk_x / 32)
            region_z = math.floor(chunk_z / 32)
            path = region_directory / f"r.{region_x}.{region_z}.mca"
            if not path.exists():
                continue
            chunk = load_chunk(path, chunk_x, chunk_z)
            if chunk is None:
                continue
            sections = chunk.get("sections", chunk.get("Sections", []))
            for section in sections:
                section_y = int(section["Y"])
                if section_y * 16 > max_y or section_y * 16 + 15 < min_y:
                    continue
                states = section.get("block_states", section.get("BlockStates"))
                if states is None:
                    continue
                palette = states.get("palette", states.get("Palette"))
                data = states.get("data", states.get("Data"))
                bits = max(4, (len(palette) - 1).bit_length()) if len(palette) > 1 else 0
                indices = unpack(data if data is not None else [], bits)
                for local_y in range(16):
                    y = section_y * 16 + local_y
                    if not min_y <= y <= max_y:
                        continue
                    for local_z in range(16):
                        z = chunk_z * 16 + local_z
                        if not min_z <= z <= max_z:
                            continue
                        for local_x in range(16):
                            x = chunk_x * 16 + local_x
                            if not min_x <= x <= max_x:
                                continue
                            palette_index = indices[(local_y * 16 + local_z) * 16 + local_x]
                            if palette_index >= len(palette):
                                raise SystemExit(
                                    f"palette index outside palette in chunk {chunk_x},{chunk_z}"
                                )
                            state = palette[palette_index]
                            result.append(
                                {
                                    "x": x,
                                    "y": y,
                                    "z": z,
                                    "block": str(state["Name"]),
                                    "properties": {
                                        str(key): str(value)
                                        for key, value in state.get("Properties", {}).items()
                                    },
                                }
                            )
    return result


def parse_arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path, help="dimension directory containing region/")
    parser.add_argument("min_x", type=int)
    parser.add_argument("max_x", type=int)
    parser.add_argument("min_y", type=int)
    parser.add_argument("max_y", type=int)
    parser.add_argument("min_z", type=int)
    parser.add_argument("max_z", type=int)
    parser.add_argument("--output", type=Path, help="write UTF-8 JSON instead of stdout")
    arguments = parser.parse_args()
    if arguments.min_x > arguments.max_x:
        parser.error("min_x must be <= max_x")
    if arguments.min_y > arguments.max_y:
        parser.error("min_y must be <= max_y")
    if arguments.min_z > arguments.max_z:
        parser.error("min_z must be <= max_z")
    return arguments


def main() -> None:
    arguments = parse_arguments()
    blocks = inspect_region(
        arguments.world,
        (
            arguments.min_x,
            arguments.max_x,
            arguments.min_y,
            arguments.max_y,
            arguments.min_z,
            arguments.max_z,
        ),
    )
    output = json.dumps(blocks, ensure_ascii=False, indent=2) + "\n"
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(output, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(output)


if __name__ == "__main__":
    main()
