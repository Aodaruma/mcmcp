"""Offline checks for immutable receipt preservation and failed temporary writes."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
PWSH = shutil.which("pwsh")


@unittest.skipUnless(PWSH, "PowerShell required")
class ProgressJsonTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mcmcp-progress-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copy2(HERE / "BuildProgressJson.ps1", self.root)
        self.data = {
            "owner": "fixture", "pending": None, "floor_receipts": [
                {"index": 0, "x": -832, "y": 79, "block": "minecraft:black_concrete",
                 "large_integer": 9223372036854775807, "extra": [None, True, "看板\\\"\n"]},
                {"index": 1, "x": -831, "verification": "confirmed", "fraction": 0.125}],
            "torch_receipts": [], "unknown_metadata": {"keep": [1, False, "日本語"]},
        }
        self.write_input()

    def write_input(self):
        (self.root / "input.json").write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")

    def run_ps(self, source, success=True):
        path = self.root / "run.ps1"
        path.write_text("$ErrorActionPreference='Stop'\n. \"$PSScriptRoot/BuildProgressJson.ps1\"\n"
                        "Set-Location $PSScriptRoot\n" + source, encoding="utf-8")
        result = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(path)],
                                cwd=self.root, capture_output=True, timeout=30)
        self.assertEqual(result.returncode == 0, success, result.stderr.decode("utf-8", errors="replace"))
        return result.stdout.decode("utf-8")

    def test_receipts_survive_metadata_changes_and_source_replacement(self):
        self.run_ps("""
$p=Read-BuildProgressJson 'input.json'
$last=Get-BuildProgressLastFloor $p
if($last.index -ne 1 -or $last.x -ne -831 -or $p.floor_receipts.GetArrayLength() -ne 2){throw 'last_floor'}
[IO.File]::Move((Join-Path $PSScriptRoot 'input.json'),(Join-Path $PSScriptRoot 'old.json'))
$p.owner='next'
$p.position=[pscustomobject]@{x=-831.5;y=80;z=192.5}
Write-BuildProgressJsonTemp $p 'output.json'
""")
        result = json.loads((self.root / "output.json").read_text(encoding="utf-8-sig"))
        expected = dict(self.data, owner="next", position={"x": -831.5, "y": 80, "z": 192.5})
        self.assertEqual(result, expected)

    def test_missing_or_non_array_receipts_fail(self):
        for value in (None, {}, "not an array"):
            with self.subTest(value=value):
                self.data["torch_receipts"] = value
                self.write_input()
                self.run_ps("$p=Read-BuildProgressJson 'input.json'", success=False)
        del self.data["torch_receipts"]
        self.write_input()
        self.run_ps("$p=Read-BuildProgressJson 'input.json'", success=False)

    def test_utf8_bom_and_deep_receipt_data_survive(self):
        nested = {"value": "retained"}
        for _ in range(65):
            nested = {"child": nested}
        self.data["floor_receipts"][0]["nested"] = nested
        self.write_input()
        path = self.root / "input.json"
        path.write_bytes(b"\xef\xbb\xbf" + path.read_bytes())
        self.run_ps("$p=Read-BuildProgressJson 'input.json'; Write-BuildProgressJsonTemp $p 'output.json'")
        self.assertEqual(json.loads((self.root / "output.json").read_text()), self.data)

    def test_empty_floor_cannot_supply_a_checkpoint(self):
        self.data["floor_receipts"] = []
        self.write_input()
        self.run_ps("$p=Read-BuildProgressJson 'input.json'; Get-BuildProgressLastFloor $p", success=False)

    def test_invalid_receipts_do_not_overwrite_temporary_or_canonical_file(self):
        (self.root / "output.json").write_text("sentinel", encoding="utf-8")
        before = (self.root / "input.json").read_bytes()
        self.run_ps("$p=Read-BuildProgressJson 'input.json'; $p.floor_receipts=@(); "
                    "Write-BuildProgressJsonTemp $p 'output.json'", success=False)
        self.assertEqual((self.root / "input.json").read_bytes(), before)
        self.assertEqual((self.root / "output.json").read_text(), "sentinel")

    def test_locked_destination_fails_without_touching_canonical_file(self):
        before = (self.root / "input.json").read_bytes()
        self.run_ps("""
$p=Read-BuildProgressJson 'input.json'
$locked=[IO.File]::Open((Join-Path $PSScriptRoot 'output.json'),'Create','Write','None')
try { Write-BuildProgressJsonTemp $p 'output.json' } finally { $locked.Dispose() }
""", success=False)
        self.assertEqual((self.root / "input.json").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
