"""构建前来源集合回归，不构建固件、不操作设备。"""
import tempfile
import unittest
from pathlib import Path
from build_factory_package import validate_source_members


class SourceMembersTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "apks").mkdir()
        (self.root / "apks/known.apk").write_bytes(b"approved")
        self.expected = {"apks/known.apk": [8, "unused"]}

    def test_exact_members(self):
        validate_source_members(self.root, self.expected)

    def test_extra_apk_rejected_before_packaging(self):
        (self.root / "apks/unapproved.apk").write_bytes(b"not approved")
        with self.assertRaises(SystemExit):
            validate_source_members(self.root, self.expected)

    def test_nested_and_unknown_files_rejected(self):
        for name in ("runtime/private.json", "system_payload/private.txt", "note.txt"):
            with self.subTest(name=name):
                path = self.root / name
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(b"not approved")
                with self.assertRaises(SystemExit):
                    validate_source_members(self.root, self.expected)
                path.unlink()

    def test_missing_rejected(self):
        (self.root / "apks/known.apk").unlink()
        with self.assertRaises(SystemExit):
            validate_source_members(self.root, self.expected)

    def test_only_named_build_metadata_exempt(self):
        (self.root / "oat-sources.json").write_text("[]")
        validate_source_members(self.root, self.expected)
        (self.root / "arbitrary-build-metadata.json").write_text("{}")
        with self.assertRaises(SystemExit):
            validate_source_members(self.root, self.expected)

    def test_unsafe_source_key_rejected(self):
        for key in ("../outside", "/absolute", "apks/../known.apk", "apks\\known.apk"):
            with self.subTest(key=key), self.assertRaises(SystemExit):
                validate_source_members(self.root, {key: [0, "unused"]})


if __name__ == "__main__":
    unittest.main()
