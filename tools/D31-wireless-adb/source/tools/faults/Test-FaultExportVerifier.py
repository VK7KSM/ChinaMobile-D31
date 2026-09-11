"""宿主包校验回归；使用隔离临时ZIP，不连接设备。"""
import hashlib
import importlib.util
import json
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("verifier", Path(__file__).with_name("Verify-FaultExport.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class VerifierTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="fault-export-verifier-"))
        self.event = "a" * 64
        self.files = {"event.json": b"{}", "state.json": b"{}", "export-seal.json": b"{}",
                      "attempt-1/fault-source.bin": b"\x00\xff\r\nactual-original"}

    def fixture(self, change_manifest=None, change_entry=None, extra=None, duplicate=False):
        items = [{"path": key, "bytes": len(value), "sha256": hashlib.sha256(value).hexdigest(),
                  "role": "RAW" if key.endswith(".bin") else "METADATA"} for key, value in self.files.items()]
        manifest = {"schemaVersion": 1, "kind": "FAULT_EVENT_BUNDLE", "eventId": self.event,
                    "scope": "ALL_FROZEN_EVENT_FILES", "files": items, "totalBytes": sum(map(len, self.files.values())),
                    "rawFiles": 1, "gaps": [{"code": "PRE_FAULT_CONTINUOUS_LOGS_UNAVAILABLE"}]}
        if change_manifest:
            change_manifest(manifest)
        data = json.dumps(manifest).encode()
        path = self.root / "bundle.zip"
        with zipfile.ZipFile(path, "x", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("manifest.json", data)
            for key, value in self.files.items():
                name = "evidence/" + key
                if change_entry:
                    name, value = change_entry(name, value)
                archive.writestr(name, value)
            if extra:
                archive.writestr(*extra)
            if duplicate:
                archive.writestr("manifest.json", data)
        receipt = {"schemaVersion": 1, "kind": "FAULT_EVENT_EXPORT", "eventId": self.event, "state": "EXPORTED",
                   "bytes": path.stat().st_size, "sha256": verifier.digest_file(path), "manifestBytes": len(data),
                   "manifestSha256": hashlib.sha256(data).hexdigest(), "files": len(items), "evidenceBytes": sum(map(len, self.files.values()))}
        return path, receipt

    def test_valid_binary_package_gives_parameters_without_executing_them(self):
        path, receipt = self.fixture()
        result = verifier.verify(path, receipt, self.event)
        self.assertFalse(result["deviceArchiveExecuted"])
        self.assertEqual(1, result["rawFiles"])
        self.assertEqual(["archive", self.event, receipt["sha256"], str(receipt["bytes"]), receipt["manifestSha256"]], result["archiveArgs"])
        self.assertFalse(result["sourceLogComplete"])
        self.assertEqual([{"code": "PRE_FAULT_CONTINUOUS_LOGS_UNAVAILABLE"}], result["gaps"])

    def source_fixture(self, capture="COMPLETE", source_state="COMPLETE", size=None, sha=None, empty=False):
        if empty:
            self.files["attempt-1/fault-source.bin"] = b""
        raw = self.files["attempt-1/fault-source.bin"]
        item = {"artifact": "fault-source.bin", "state": source_state, "storedBytes": len(raw),
                "storedSha256": hashlib.sha256(raw).hexdigest() if sha is None else sha,
                "before": {"size": len(raw) if size is None else size}}
        self.files["state.json"] = json.dumps({"phase": "PARTIAL", "capture": capture}).encode()
        self.files["attempt-1/report.json"] = json.dumps({"diagnostic": {"state": capture, "items": [item]}}).encode()
        path, receipt = self.fixture(change_manifest=lambda m: m.update(rawEvidence={"captureState": capture}))
        receipt["captureState"] = capture
        return path, receipt

    def test_complete_source_with_window_gaps_remains_distinct(self):
        path, receipt = self.source_fixture()
        result = verifier.verify(path, receipt, self.event)
        self.assertTrue(result["sourceLogComplete"])
        self.assertEqual(1, len(result["completeSourceFiles"]))
        self.assertEqual(1, result["gapCount"])

    def test_capture_partial_cannot_be_called_complete(self):
        path, receipt = self.source_fixture(capture="PARTIAL")
        self.assertFalse(verifier.verify(path, receipt, self.event)["sourceLogComplete"])

    def test_source_partial_cannot_be_called_complete(self):
        path, receipt = self.source_fixture(source_state="TRUNCATED")
        self.assertFalse(verifier.verify(path, receipt, self.event)["sourceLogComplete"])

    def test_original_source_larger_than_saved_bytes_is_not_complete(self):
        path, receipt = self.source_fixture(size=99999)
        self.assertFalse(verifier.verify(path, receipt, self.event)["sourceLogComplete"])

    def test_wrong_original_receipt_hash_is_not_complete(self):
        path, receipt = self.source_fixture(sha="0" * 64)
        self.assertFalse(verifier.verify(path, receipt, self.event)["sourceLogComplete"])

    def test_empty_raw_is_not_nonempty_fault_evidence(self):
        path, receipt = self.source_fixture(empty=True)
        self.assertFalse(verifier.verify(path, receipt, self.event)["sourceLogComplete"])

    def test_wrong_event(self):
        path, receipt = self.fixture()
        with self.assertRaises(ValueError): verifier.verify(path, receipt, "b" * 64)

    def test_wrong_package_hash(self):
        path, receipt = self.fixture(); receipt["sha256"] = "b" * 64
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_wrong_package_length(self):
        path, receipt = self.fixture(); receipt["bytes"] += 1
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_inner_corruption_despite_correct_outer_hash(self):
        path, receipt = self.fixture(change_entry=lambda name, value: (name, b"changed") if name.endswith(".bin") else (name, value))
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_traversal_manifest(self):
        path, receipt = self.fixture(change_manifest=lambda m: m["files"][0].update(path="../outside"))
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_unlisted_extra_file(self):
        path, receipt = self.fixture(extra=("unlisted", b"x"))
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_duplicate_zip_entries(self):
        path, receipt = self.fixture(duplicate=True)
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_zip_symlink(self):
        link = zipfile.ZipInfo("link"); link.create_system = 3; link.external_attr = (stat.S_IFLNK | 0o777) << 16
        path, receipt = self.fixture(extra=(link, b"../../outside"))
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_duplicate_json_keys(self):
        with self.assertRaises(ValueError): verifier.parse_json(b'{"eventId":"a","eventId":"b"}')

    def test_wrong_manifest_hash(self):
        path, receipt = self.fixture(); receipt["manifestSha256"] = "c" * 64
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)

    def test_raw_count_cannot_claim_missing_originals(self):
        path, receipt = self.fixture(change_manifest=lambda m: m.update(rawFiles=2))
        with self.assertRaises(ValueError): verifier.verify(path, receipt, self.event)


if __name__ == "__main__":
    unittest.main(verbosity=2)
