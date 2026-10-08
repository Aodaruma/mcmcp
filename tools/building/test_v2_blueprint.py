import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from compile_v2_blueprint import compile_blocks, load, write_project


class BlueprintTests(unittest.TestCase):
    def test_full_128_by_128_by_9_design_is_lossless_and_bounded(self):
        data = {"size": [128, 9, 128], "palette": ["minecraft:white_concrete", "minecraft:black_concrete"],
                "blocks": [i % 2 for i in range(128 * 128 * 9)]}
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "project"
            manifest = write_project(data, output, "a" * 64, (100, 64, -200))
            self.assertEqual(manifest["placements"], 147456)
            self.assertEqual(len(manifest["chunks"]), 36)
            self.assertEqual(manifest["materials"], {"minecraft:black_concrete": 73728, "minecraft:white_concrete": 73728})
            self.assertEqual(manifest["bounds"], {"x": [100, 227], "y": [64, 72], "z": [-200, -73]})
            seen = set()
            for chunk in manifest["chunks"]:
                raw = (output / chunk["path"]).read_bytes()
                self.assertLessEqual(len(raw), 2097152)
                self.assertEqual(hashlib.sha256(raw).hexdigest(), chunk["sha256"])
                steps = json.loads(raw)["steps"]
                self.assertEqual(len(steps), 4096)
                for step in steps:
                    at = tuple(step[k] for k in ("x", "y", "z"))
                    self.assertNotIn(at, seen)
                    seen.add(at)
            self.assertEqual(len(seen), 147456)
            with self.assertRaises(FileExistsError):
                write_project(data, output, "a" * 64)

    def test_csv_quotes_properties_and_no_code_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "data.csv"
            path.write_text('x,y,z,block,properties\n1,2,3,minecraft:oak_log,"{""axis"":""x""}"\n', encoding="utf-8")
            data, _ = load(path, 1024)
            steps, _ = compile_blocks(data)
            self.assertEqual(steps[0]["properties"], {"axis": "x"})
            path.write_bytes(b'x,y,z,block\r1,2,3,minecraft:stone\r2,2,3,minecraft:dirt\r')
            data, _ = load(path, 1024)
            self.assertEqual(len(compile_blocks(data)[0]), 2)
            with self.assertRaises(ValueError):
                compile_blocks({"blocks": [{"x": 0, "y": 0, "z": 0, "block": 'minecraft:stone;evil()'}]})
            duplicate = path.with_suffix('.json')
            duplicate.write_text('{"blocks":[],"blocks":[]}', encoding="utf-8")
            with self.assertRaises(ValueError):
                load(duplicate, 1024)

    def test_rejects_duplicate_positions_bad_indices_and_cell_overflow(self):
        row = {"x": 0, "y": 0, "z": 0, "block": "minecraft:stone"}
        for data, limit in [({"blocks": [row, row]}, 10), ({"blocks": [row]}, 0),
                            ({"size": [1,1,1], "palette": ["minecraft:stone"], "blocks": [2]}, 10)]:
            with self.assertRaises(ValueError):
                compile_blocks(data, max_cells=limit)

    def test_air_is_not_an_implicit_break_and_layer_order_is_bottom_up(self):
        steps, material = compile_blocks({"blocks": [
            {"x": 0, "y": 1, "z": 0, "block": "minecraft:stone"},
            {"x": 0, "y": 0, "z": 0, "block": "minecraft:dirt"},
            {"x": 1, "y": 0, "z": 0, "block": "minecraft:air"}]})
        self.assertEqual([s["y"] for s in steps], [0,1])
        self.assertEqual(sum(material.values()), 2)


if __name__ == "__main__":
    unittest.main()
