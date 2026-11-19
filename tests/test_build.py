import argparse
import contextlib
from datetime import date
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
spec = importlib.util.spec_from_file_location("private_build_runner", Path(__file__).resolve().parent.parent / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class BuildRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="entiny-build-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "checkout"
        self.wrapper = self.repo / "worktree/gradle/wrapper/gradle-wrapper.jar"
        self.write(self.wrapper, b"fixture wrapper")
        self.write(self.repo / "package.json", b"{}")
        self.write(self.repo / "worktree/gradle.properties", b"APP_VERSION_NAME=13.1.1\r\n")
        self.bundle = self.base / "bundle"
        self.enterContext(patch.object(build, "__file__", str(self.bundle / "build.py")))
        self.config = self.base / "config.yaml"
        self.before = b"\xef\xbb\xbf# local build\r\nbuild version: '2026110600' # retained\r\n"
        self.config.write_bytes(self.before)
        self.java_home = self.base / "jdk"
        self.java = self.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
        self.bun = self.base / "bun"
        self.write(self.java, b"fixture java")
        self.write(self.bun, b"fixture bun")
        self.args = argparse.Namespace(repo=self.repo, config=self.config, abi="x86_64", build_type="beta",
                                       upstream=self.base / "upstream", runtime_upstream=self.base / "runtime",
                                       dry_run=False)
        self.stdout = self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.enterContext(patch.dict(os.environ, {"JAVA_HOME": str(self.java_home), "INU_BUILD": "inherited",
                                                  "INU_BUILD_TYPE": "inherited", "ABI_FILTERS": "inherited"}))
        self.enterContext(patch.object(build.shutil, "which", side_effect=lambda name: str(self.bun) if name == "bun" else str(self.java)))
        next_version = build.next_version
        self.enterContext(patch.object(build, "next_version", side_effect=lambda old: next_version(old, date(2026, 11, 6))))
        self.install = self.enterContext(patch.object(build.install, "run"))
        self.uninstall = self.enterContext(patch.object(build.install, "uninstall"))
        self.process = self.enterContext(patch.object(build.subprocess, "run"))

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def snapshot(self):
        return {path.relative_to(self.repo): path.read_bytes() for path in self.repo.rglob("*") if path.is_file()}

    def artifact(self, version="2026110601", filename="app.apk", count=1):
        variant = "debug" if self.args.build_type == "debug" else "release"
        directory = self.repo / "worktree/TMessagesProj_App/build/outputs/apk" / variant
        directory.mkdir(parents=True, exist_ok=True)
        self.write((directory / filename).resolve(), b"fixture apk")
        metadata = {"elements": [{"versionCode": int(version), "outputFile": filename}] * count}
        (directory / "output-metadata.json").write_text(json.dumps(metadata), encoding="utf-8")

    def test_success_uses_exact_environment_and_saves_only_after_matching_apk(self):
        for kind, abi, variant in (("beta", "x86_64", "Release"), ("debug", "arm64-v8a", "Debug"),
                                   ("release", "arm64-v8a", "Release")):
            with self.subTest(kind=kind):
                self.config.write_bytes(self.before)
                self.args.build_type, self.args.abi = kind, abi
                self.process.reset_mock()
                self.install.reset_mock()
                if kind == "debug":
                    os.environ["CHAQUOPY_BUILD_PYTHON"] = "explicit-python"
                else:
                    os.environ.pop("CHAQUOPY_BUILD_PYTHON", None)

                def execute(command, **kwargs):
                    self.assertEqual(self.config.read_bytes(), self.before)
                    self.assertTrue(self.config.with_name("config.yaml.lock").is_file())
                    self.assertTrue(kwargs["check"])
                    self.assertEqual({key: kwargs["env"][key] for key in ("INU_BUILD", "INU_BUILD_TYPE", "ABI_FILTERS")},
                                     {"INU_BUILD": "2026110601", "INU_BUILD_TYPE": kind, "ABI_FILTERS": abi})
                    self.assertEqual(kwargs["env"]["CHAQUOPY_BUILD_PYTHON"],
                                     "explicit-python" if kind == "debug" else sys.executable)
                    if command[0] == str(self.java):
                        self.artifact()
                    return subprocess.CompletedProcess(command, 0)

                self.process.side_effect = execute
                architecture = "arm64" if abi == "arm64-v8a" else "x86_64"
                output = self.bundle / "var" / "out" / f"entinygram-private-{architecture}-13.1.1-2026110601.apk"
                save_version = build.save_version

                def save_after_copy(path, before, version):
                    self.assertEqual(output.read_bytes(), b"fixture apk")
                    self.assertEqual(path.read_bytes(), self.before)
                    save_version(path, before, version)

                with patch.object(build, "save_version", side_effect=save_after_copy):
                    self.assertEqual(build.run(self.args), "2026110601")
                self.assertEqual(output.read_bytes(), b"fixture apk")
                self.install.assert_called_once_with(self.repo, self.args.upstream, self.args.runtime_upstream)
                commands = self.process.call_args_list
                self.assertEqual(len(commands), 2)
                self.assertEqual(commands[0].args[0], [str(self.bun), "run", "generate-grants"])
                self.assertEqual(commands[0].kwargs["cwd"], self.repo)
                self.assertEqual(commands[1].args[0], [str(self.java), "-Dorg.gradle.appname=gradlew", "-classpath",
                    str(self.wrapper), "org.gradle.wrapper.GradleWrapperMain", "--no-daemon",
                    "-Pinu.pluginless=false", ":TMessagesProj_App:assemble" + variant])
                self.assertEqual(commands[1].kwargs["cwd"], self.repo / "worktree")
                self.assertEqual(self.config.read_bytes(), self.before.replace(b"2026110600", b"2026110601"))
                self.assertFalse(self.config.with_name("config.yaml.lock").exists())
                self.assertEqual(os.environ["INU_BUILD"], "inherited")
                output.unlink()

    def test_failures_never_advance_config(self):
        initial = self.snapshot()
        for stage in ("install", "grants", "gradle"):
            with self.subTest(stage=stage):
                self.install.reset_mock(side_effect=True)
                self.process.reset_mock(side_effect=True)
                if stage == "install":
                    self.install.side_effect = build.install.InstallError("fixture failure")
                else:
                    def execute(command, **_kwargs):
                        if (stage == "grants") == (command[0] == str(self.bun)):
                            raise subprocess.CalledProcessError(1, command)
                        return subprocess.CompletedProcess(command, 0)
                    self.process.side_effect = execute
                with self.assertRaises((build.install.InstallError, subprocess.CalledProcessError)):
                    build.run(self.args)
                self.assertEqual(self.config.read_bytes(), self.before)
                self.assertEqual(self.snapshot(), initial)
                self.assertFalse(self.config.with_name("config.yaml.lock").exists())
        self.install.side_effect = None
        for version, filename, count in (("2026110600", "app.apk", 1), ("2026110601", "app.apk", 2),
                                         ("2026110601", "../escaped.apk", 1)):
            with self.subTest(metadata=(version, filename, count)):
                self.process.side_effect = lambda *_args, **_kwargs: self.artifact(version, filename, count)
                with self.assertRaises(ValueError):
                    build.run(self.args)
                self.assertEqual(self.config.read_bytes(), self.before)
                self.assertFalse(self.config.with_name("config.yaml.lock").exists())

    def test_overlay_is_taken_out_after_the_build_unless_kept(self):
        self.process.side_effect = lambda *_args, **_kwargs: self.artifact("2026110601")
        build.run(self.args)
        self.uninstall.assert_called_once_with(self.repo.resolve())
        self.uninstall.reset_mock()
        self.process.side_effect = subprocess.CalledProcessError(1, "gradle")
        with self.assertRaises(subprocess.CalledProcessError):
            build.run(self.args)
        self.uninstall.assert_called_once_with(self.repo.resolve())
        self.uninstall.reset_mock()
        self.process.side_effect = lambda *_args, **_kwargs: self.artifact("2026110602")
        self.args.keep_applied = True
        (self.base / "out").mkdir(exist_ok=True)
        for leftover in (self.base / "out").glob("*"):
            leftover.unlink()
        build.run(self.args)
        self.uninstall.assert_not_called()

    def test_dry_run_and_existing_lock_do_not_run_tools_or_write_host(self):
        initial = self.snapshot()
        self.args.dry_run = True
        self.assertEqual(build.run(self.args), "2026110601")
        self.assertIn("-Pinu.pluginless=false", self.stdout.getvalue())
        self.assertIn("entinygram-private-x86_64-13.1.1-2026110601.apk", self.stdout.getvalue())
        self.assertFalse((self.bundle / "var" / "out").exists())
        self.assertEqual(self.config.read_bytes(), self.before)
        self.assertEqual(self.snapshot(), initial)
        self.assertFalse(self.config.with_name("config.yaml.lock").exists())
        self.args.dry_run = False
        lock = self.config.with_name("config.yaml.lock")
        lock.write_text("another process", encoding="ascii")
        with self.assertRaisesRegex(ValueError, "another build owns"):
            build.run(self.args)
        self.assertEqual(lock.read_text(encoding="ascii"), "another process")
        self.assertEqual(self.config.read_bytes(), self.before)
        self.install.assert_not_called()
        self.process.assert_not_called()

    def test_output_collision_and_copy_error_preserve_config(self):
        self.args.abi = "arm64-v8a"
        output = self.bundle / "var/out/entinygram-private-arm64-13.1.1-2026110601.apk"
        self.write(output, b"previous artifact")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "APK already exists"):
            build.run(self.args)
        self.assertEqual(output.read_bytes(), b"previous artifact")
        self.assertEqual(self.config.read_bytes(), self.before)
        self.assertEqual(self.snapshot(), before)
        self.install.assert_not_called()
        self.process.assert_not_called()
        output.unlink()
        self.process.side_effect = lambda *_args, **_kwargs: self.artifact()

        def fail_copy(_source, destination):
            destination.write(b"partial artifact")
            raise OSError("fixture copy failure")

        with patch.object(build.shutil, "copyfileobj", side_effect=fail_copy):
            with self.assertRaisesRegex(OSError, "fixture copy failure"):
                build.run(self.args)
        self.assertFalse(output.exists())
        self.assertEqual(self.config.read_bytes(), self.before)
        self.assertFalse(self.config.with_name("config.yaml.lock").exists())

    def test_config_changed_during_build_is_preserved(self):
        changed = self.before + b"# user edit\r\n"

        def execute(command, **_kwargs):
            if command[0] == str(self.java):
                self.artifact()
                self.config.write_bytes(changed)

        self.process.side_effect = execute
        with self.assertRaisesRegex(ValueError, "changed during the build"):
            build.run(self.args)
        self.assertEqual(self.config.read_bytes(), changed)
        self.assertFalse(self.config.with_name("config.yaml.lock").exists())


class BuildVersionTests(unittest.TestCase):
    def test_calendar_counters_and_invalid_config(self):
        for previous, today, expected in (("2026110600", date(2026, 11, 6), "2026110601"),
                                          ("2026110699", date(2026, 11, 7), "2026110700"),
                                          ("2026123199", date(2027, 1, 1), "2027010100"),
                                          ("2024022807", date(2024, 2, 29), "2024022900"),
                                          ("2024022999", date(2024, 3, 1), "2024030100")):
            with self.subTest(previous=previous, today=today):
                self.assertEqual(build.next_version(previous, today), expected)
        for previous in ("202611060", "not-a-date", "2026023000", "2026110700", "2026110699"):
            with self.subTest(invalid=previous), self.assertRaises(ValueError):
                build.next_version(previous, date(2026, 11, 6))
        with self.assertRaisesRegex(ValueError, "integer versionCode"):
            build.next_version("2147123100", date(2148, 1, 1))
        with tempfile.TemporaryDirectory(prefix="entiny-build-config-test-") as temporary:
            config = Path(temporary) / "config.yaml"
            for value in (b"", b"build version: 202611060\n", b"unknown: value\n",
                          b"build version: 2026110600\nbuild version: 2026110601\n"):
                with self.subTest(config=value), self.assertRaises(ValueError):
                    config.write_bytes(value)
                    build.read_config(config)


if __name__ == "__main__":
    unittest.main()
