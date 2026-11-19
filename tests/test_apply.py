import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("private_plugin_installer", Path(__file__).resolve().parent.parent / "apply.py")
installer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = installer
spec.loader.exec_module(installer)


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="entiny-installer-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "checkout"
        self.bundle = self.base / "bundle"
        self.upstream = self.base / "upstream"
        self.repo.mkdir()
        self.bundle.mkdir()
        self.upstream.mkdir()
        self.write(self.upstream / "engine/Engine.java", b"class Engine {\n    old();\n}\n")
        self.write(self.upstream / "engine/ui/Foreign.java", b"foreign screen\n")
        self.write(self.upstream / "runtime/native.c", b"runtime\n")
        self.git("init", "--quiet")
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--quiet", "-m", "fixture")
        commit = self.git("rev-parse", "HEAD").decode().strip()
        self.write(self.repo / "src/Config.kt", b"\xef\xbb\xbfval ready = false\r\n// untouched \xce\xb1\r\n")
        self.write(self.repo / "src/res/values/strings.xml",
                   b'<?xml version="1.0" encoding="utf-8"?>\r\n<resources>\r\n'
                   b'    <string name="existing">same &amp; value</string>\r\n'
                   b'    <string name="unrelated">keep</string>\r\n</resources>\r\n')
        self.write(self.bundle / "payload/Bridge.kt", b"class Bridge\n")
        self.write(self.bundle / "payload/strings.xml",
                   b'<resources>\n<string name="existing">same &amp; value</string>\n'
                   b'<string name="added">new</string>\n</resources>\n')
        source = {"url": str(self.upstream), "commit": commit}
        self.manifest = {
            "upstream": source,
            "runtime_upstream": source,
            "vendor": [{"source": "engine", "target": "extera-plugins/engine", "exclude": ["ui"]}],
            "runtime_vendor": [{"source": "runtime", "target": "extera-plugins/runtime"}],
            "vendor_edits": [{"path": "extera-plugins/engine/Engine.java",
                              "before": "    old();", "after": "    adapted();"}],
            "files": [{"source": "payload/Bridge.kt", "target": "src/Bridge.kt"}],
            "edits": [{"path": "src/Config.kt", "before": "val ready = false",
                       "alternatives": ["val ready = draft"],
                       "after": "val ready = false\nval python = true"}],
            "resources": [{"source": "payload/strings.xml", "target": "src/res/values/strings.xml"}],
        }

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.upstream), *arguments], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    def snapshot(self):
        return {p.relative_to(self.repo).as_posix(): p.read_bytes()
                for p in self.repo.rglob("*") if p.is_file()}

    def run_installer(self, check=False):
        (self.bundle / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            return installer.run(self.repo, self.upstream, self.upstream, check, self.bundle)

    def test_offline_preflight_apply_and_repeat_preserve_format(self):
        before = self.snapshot()
        plan = self.run_installer(check=True)
        self.assertEqual(len(plan.changes), 5)
        self.assertEqual(self.snapshot(), before)
        self.assertFalse((self.bundle / "var" / "state").exists())
        self.run_installer()
        config = (self.repo / "src/Config.kt").read_bytes()
        self.assertEqual(config, b"\xef\xbb\xbfval ready = false\r\nval python = true\r\n// untouched \xce\xb1\r\n")
        self.assertFalse((self.repo / "extera-plugins/engine/ui").exists())
        self.assertIn(b"adapted();", (self.repo / "extera-plugins/engine/Engine.java").read_bytes())
        resources = (self.repo / "src/res/values/strings.xml").read_bytes()
        self.assertIn(b'<string name="unrelated">keep</string>\r\n', resources)
        self.assertIn(b'<string name="added">new</string>\r\n', resources)
        self.assertNotIn(b"\n", resources.replace(b"\r\n", b""))
        applied = self.snapshot()
        transactions = list((self.bundle / "var" / "state").rglob("transaction.json"))
        self.assertEqual(len(transactions), 1)
        self.assertEqual(self.run_installer().changes, [])
        self.assertEqual(self.snapshot(), applied)
        self.assertEqual(list((self.bundle / "var" / "state").rglob("transaction.json")), transactions)
        self.assertEqual(self.git("status", "--porcelain"), b"")

    def commit_checkout(self):
        for arguments in (("init", "--quiet"), ("add", "."),
                          ("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                           "commit", "--quiet", "-m", "original")):
            subprocess.run(["git", "-C", str(self.repo), *arguments], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def run_uninstaller(self, check=False):
        with contextlib.redirect_stdout(io.StringIO()):
            return installer.uninstall(self.repo, self.bundle, check)

    def test_uninstall_restores_the_checkout_and_keeps_foreign_edits(self):
        self.commit_checkout()
        original = self.snapshot()
        self.run_installer()
        config = self.repo / "src/Config.kt"
        config.write_bytes(config.read_bytes() + b"// mine\r\n")
        resources = self.repo / "src/res/values/strings.xml"
        resources.write_bytes(resources.read_bytes().replace(b"</resources>", b'    <string name="mine">x</string>\r\n</resources>'))
        before = self.snapshot()
        self.run_uninstaller(check=True)
        self.assertEqual(self.snapshot(), before)
        self.run_uninstaller()
        restored = {k: v for k, v in self.snapshot().items() if not k.startswith("extera-plugins/")}
        self.assertFalse((self.repo / "src/Bridge.kt").exists())
        self.assertEqual(restored["src/Config.kt"], original["src/Config.kt"] + b"// mine\r\n")
        self.assertIn(b'<string name="mine">x</string>', restored["src/res/values/strings.xml"])
        self.assertNotIn(b'name="added"', restored["src/res/values/strings.xml"])
        self.assertIn(b'<string name="existing">same &amp; value</string>', restored["src/res/values/strings.xml"])
        self.assertFalse(list((self.bundle / "var" / "state").rglob("installed.json")))
        self.assertEqual(self.run_uninstaller().__len__(), 0)
        self.run_installer()
        self.assertIn(b"val python = true", config.read_bytes())

    def test_uninstall_refuses_a_payload_file_changed_by_hand(self):
        self.run_installer()
        (self.repo / "src/Bridge.kt").write_bytes(b"class Bridge { changed }\n")
        before = self.snapshot()
        with self.assertRaises(installer.InstallError):
            self.run_uninstaller()
        self.assertEqual(self.snapshot(), before)

    def test_unknown_payload_conflict_aborts_before_any_write(self):
        self.write(self.repo / "src/Bridge.kt", b"user work\n")
        before = self.snapshot()
        with self.assertRaisesRegex(installer.InstallError, "unknown existing content"):
            self.run_installer()
        self.assertEqual(self.snapshot(), before)
        self.assertFalse((self.bundle / "var" / "state").exists())

    def test_known_draft_and_alternative_are_explicitly_accepted(self):
        draft = b"known draft engine\r\n"
        self.write(self.repo / "extera-plugins/engine/Engine.java", draft)
        self.manifest["known_drafts"] = {"extera-plugins/engine/Engine.java": [installer.digest(draft)]}
        self.write(self.repo / "src/Config.kt", b"val ready = draft\r\n")
        self.run_installer()
        self.assertIn(b"adapted();\r\n", (self.repo / "extera-plugins/engine/Engine.java").read_bytes())
        self.assertEqual((self.repo / "src/Config.kt").read_bytes(), b"val ready = false\r\nval python = true\r\n")

    def test_ambiguous_anchor_aborts_before_any_write(self):
        self.write(self.repo / "src/Config.kt", b"val ready = false\nval ready = false\n")
        before = self.snapshot()
        with self.assertRaisesRegex(installer.InstallError, "anchor conflict"):
            self.run_installer()
        self.assertEqual(self.snapshot(), before)

    def test_resource_conflict_preserves_unrelated_and_pending_changes(self):
        path = self.repo / "src/res/values/strings.xml"
        path.write_bytes(path.read_bytes().replace(b"same &amp; value", b"my translation"))
        before = self.snapshot()
        with self.assertRaisesRegex(installer.InstallError, "resource conflict"):
            self.run_installer()
        self.assertEqual(self.snapshot(), before)

    def test_path_traversal_and_git_metadata_are_rejected(self):
        for target in ("../outside", "/outside", "C:\\outside", "\\\\server\\share\\x", ".git/config", "src/../outside"):
            with self.subTest(target=target):
                self.manifest["files"][0]["target"] = target
                before = self.snapshot()
                with self.assertRaises(installer.InstallError):
                    self.run_installer()
                self.assertEqual(self.snapshot(), before)

    def test_symlink_escape_is_rejected(self):
        outside = self.base / "outside"
        outside.mkdir()
        try:
            (self.repo / "escape").symlink_to(outside, target_is_directory=True)
        except OSError as error:
            self.skipTest(str(error))
        self.manifest["files"][0]["target"] = "escape/Bridge.kt"
        with self.assertRaisesRegex(installer.InstallError, "escapes"):
            self.run_installer()
        self.assertEqual(list(outside.iterdir()), [])

    def test_write_failure_rolls_back_and_keeps_backups(self):
        before = self.snapshot()
        original = installer.atomic_write
        failed = False

        def fail_once(path, content):
            nonlocal failed
            if path == self.repo / "src/Config.kt" and not failed:
                failed = True
                raise OSError("injected write failure")
            original(path, content)

        with patch.object(installer, "atomic_write", fail_once):
            with self.assertRaisesRegex(installer.InstallError, "target changes rolled back"):
                self.run_installer()
        self.assertTrue(failed)
        self.assertEqual(self.snapshot(), before)
        self.assertFalse((self.repo / "extera-plugins").exists())
        transactions = list((self.bundle / "var" / "state").rglob("transaction.json"))
        self.assertEqual(len(transactions), 1)
        records = json.loads(transactions[0].read_text())["files"]
        config = next(r for r in records if r["path"] == "src/Config.kt")
        self.assertEqual((transactions[0].parent / config["backup"]).read_bytes(), before["src/Config.kt"])
        self.assertFalse(list((self.bundle / "var" / "state").rglob("installed.json")))


    def test_split_manifest_merges_lists_and_rejects_repeated_keys(self):
        parts = self.bundle / "manifest"
        parts.mkdir(parents=True)
        (parts / "a.json").write_text(json.dumps({"upstream": {"url": "u"}, "edits": [{"path": "a"}]}), encoding="utf-8")
        (parts / "b.json").write_text(json.dumps({"edits": [{"path": "b"}], "known_drafts": {"x": []}}), encoding="utf-8")
        merged = installer.load_manifest(self.bundle)
        self.assertEqual([e["path"] for e in merged["edits"]], ["a", "b"])
        self.assertEqual(merged["known_drafts"], {"x": []})
        (parts / "c.json").write_text(json.dumps({"upstream": {}}), encoding="utf-8")
        with self.assertRaises(installer.InstallError):
            installer.load_manifest(self.bundle)


if __name__ == "__main__":
    unittest.main()
