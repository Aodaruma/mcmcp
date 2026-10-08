"""Compile data-only JSON/CSV blueprints into bounded v2 construction plans. No game access."""
import argparse
from collections import Counter
import csv
import hashlib
import io
import json
from pathlib import Path
import re

ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def integer(value):
    if isinstance(value, str) and re.fullmatch(r"-?[0-9]{1,10}", value):
        value = int(value)
    if type(value) is not int or not -30_000_000 <= value <= 30_000_000:
        raise ValueError("coordinates must be bounded integers")
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON property")
        result[key] = value
    return result


def load(path, max_bytes):
    with Path(path).open("rb") as stream:
        raw = stream.read(max_bytes + 1)
    if len(raw) > max_bytes:
        raise ValueError("blueprint exceeds --max-mib")
    text = raw.decode("utf-8-sig")
    if Path(path).suffix.lower() == ".csv":
        reader = csv.DictReader(io.StringIO(text, newline=""), strict=True)
        if not reader.fieldnames or len(set(reader.fieldnames)) != len(reader.fieldnames):
            raise ValueError("unique CSV headers required")
        data = {"blocks": list(reader)}
    else:
        data = json.loads(text, object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))
        if isinstance(data, list):
            data = {"blocks": data}
    if not isinstance(data, dict) or set(data) - {"blocks", "palette", "size", "origin", "dimension"}:
        raise ValueError("invalid blueprint keys")
    return data, hashlib.sha256(raw).hexdigest()


def compile_blocks(data, origin=(0, 0, 0), max_cells=1_048_576):
    offset = data.get("origin", [0, 0, 0])
    if not isinstance(offset, list) or len(offset) != 3:
        raise ValueError("origin requires x,y,z")
    offset = tuple(integer(integer(a) + integer(b)) for a, b in zip(offset, origin))
    rows = data.get("blocks")
    if not isinstance(rows, list) or not 1 <= len(rows) <= max_cells:
        raise ValueError("cell count exceeds --max-cells or is empty")
    if "palette" in data:
        palette = data["palette"]
        size = data.get("size")
        if not isinstance(palette, list) or not palette or not isinstance(size, list) or len(size) != 3:
            raise ValueError("palette requires nonempty palette and size [x,y,z]")
        sx, sy, sz = (integer(n) for n in size)
        if min(sx, sy, sz) < 1 or sx * sy * sz != len(rows):
            raise ValueError("palette volume mismatch")
        def cells():
            for index, value in enumerate(rows):
                if type(value) is not int or not 0 <= value < len(palette):
                    raise ValueError("invalid palette index")
                entry = palette[value]
                if isinstance(entry, str):
                    entry = {"block": entry}
                if not isinstance(entry, dict) or set(entry) - {"block", "item", "properties"}:
                    raise ValueError("invalid palette entry")
                yield {**entry, "x": index % sx, "y": index // (sx * sz), "z": index // sx % sz}
        source = cells()
    else:
        if "size" in data:
            raise ValueError("size requires palette")
        source = rows
    steps, seen, materials = [], set(), Counter()
    for row in source:
        if not isinstance(row, dict) or set(row) - {"x", "y", "z", "block", "item", "properties"}:
            raise ValueError("invalid coordinate row")
        position = tuple(integer(integer(row[k]) + offset[i]) for i, k in enumerate(("x", "y", "z")))
        if position in seen:
            raise ValueError("duplicate blueprint coordinate")
        seen.add(position)
        block = row.get("block")
        item = row.get("item") or block
        if not all(isinstance(v, str) and len(v) <= 128 and ID.fullmatch(v) for v in (block, item)):
            raise ValueError("invalid block/item registry ID")
        if block in AIR:
            continue  # Empty design cells never implicitly authorize destruction.
        if not block.startswith("minecraft:") or not item.startswith("minecraft:"):
            raise ValueError("v2 placement currently requires minecraft block/item IDs")
        properties = row.get("properties") or {}
        if isinstance(properties, str):
            properties = json.loads(properties, object_pairs_hook=unique_object)
        if not isinstance(properties, dict) or len(properties) > 32 or any(
                not re.fullmatch(r"[a-z0-9_]{1,64}", k) or not isinstance(v, str)
                or not re.fullmatch(r"[a-z0-9_./-]{1,64}", v) for k, v in properties.items()):
            raise ValueError("invalid block properties")
        step = dict(zip(("x", "y", "z"), position), block=block, advance=True)
        if item != block:
            step["item"] = item
        if properties:
            step["properties"] = properties
        steps.append(step)
        materials[item] += 1
    if not steps:
        raise ValueError("no non-air placements")
    # Bottom-up, alternating x direction by row; this is construction ordering,
    # not a claim that supports/materials/routes in the live world are available.
    steps.sort(key=lambda s: (s["y"], s["z"], s["x"] if s["z"] % 2 == 0 else -s["x"]))
    return steps, dict(sorted(materials.items()))


def encode(data):
    return (json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def write_project(data, output, source_hash, origin=(0, 0, 0), max_cells=1_048_576, chunk_size=4096):
    if not 1 <= chunk_size <= 4096:
        raise ValueError("chunk size must be 1..4096")
    dimension = data.get("dimension", "minecraft:overworld")
    if not isinstance(dimension, str) or not ID.fullmatch(dimension):
        raise ValueError("invalid dimension")
    steps, materials = compile_blocks(data, origin, max_cells)
    output = Path(output)
    # Do not overwrite a project with existing progress.
    output.mkdir(parents=True, exist_ok=False)
    chunks, begin = [], 0
    while begin < len(steps):
        end = min(begin + chunk_size, len(steps))
        while True:
            raw = encode({"version": 2, "dimension": dimension, "steps": steps[begin:end]})
            if len(raw) <= 2_097_152:
                break
            end = begin + (end - begin) // 2
            if end <= begin:
                raise ValueError("single placement exceeds plan byte limit")
        name = f"plan-{len(chunks):05d}.json"
        (output / name).write_bytes(raw)
        chunks.append({"path": name, "sha256": hashlib.sha256(raw).hexdigest(), "placements": end - begin})
        begin = end
    manifest = {"version": 1, "dimension": dimension, "source_sha256": source_hash,
                "placements": len(steps), "materials": materials,
                "bounds": {k: [min(s[k] for s in steps), max(s[k] for s in steps)] for k in ("x", "y", "z")},
                "chunks": chunks}
    (output / "manifest.json").write_bytes(encode(manifest))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path, help="new project directory")
    parser.add_argument("--origin", nargs=3, type=int, default=(0, 0, 0), metavar=("X", "Y", "Z"))
    parser.add_argument("--max-mib", type=int, default=256)
    parser.add_argument("--max-cells", type=int, default=1_048_576)
    parser.add_argument("--chunk-size", type=int, default=4096)
    args = parser.parse_args()
    if not 1 <= args.max_mib <= 1024 or not 1 <= args.max_cells <= 16_777_216:
        parser.error("--max-mib must be 1..1024 and --max-cells 1..16777216")
    data, digest = load(args.input, args.max_mib * 1024 * 1024)
    manifest = write_project(data, args.output, digest, args.origin, args.max_cells, args.chunk_size)
    print(json.dumps({"placements": manifest["placements"], "chunks": len(manifest["chunks"]),
                      "materials": manifest["materials"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
