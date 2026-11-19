import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

import apply as install


VERSION_LINE = re.compile(r'''build version:[ \t]*(["']?)([0-9]{10})\1[ \t]*(?:#.*)?''')


def read_config(path):
    data = path.read_bytes()
    versions = []
    for line in data.decode("utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = VERSION_LINE.fullmatch(line)
        if match is None:
            raise ValueError(f'{path}: expected build version: "YYYYMMDDNN"')
        versions.append(match.group(2))
    if len(versions) != 1:
        raise ValueError(f"{path}: expected exactly one build version")
    return data, versions[0]


def next_version(previous, today=None, allow_ahead=False):
    if not re.fullmatch(r"[0-9]{10}", previous):
        raise ValueError("build version must use YYYYMMDDNN")
    previous_day = datetime.strptime(previous[:8], "%Y%m%d").date()
    today = today or datetime.now(timezone.utc).date()
    if previous_day > today:
        if not allow_ahead:
            raise ValueError("config date is ahead of the current UTC date")
        version = str(int(previous) + 1)
        if int(version) > 2_147_483_647:
            raise ValueError("build version exceeds Android's integer versionCode")
        return version
    counter = int(previous[8:]) + 1 if previous_day == today else 0
    if counter > 99:
        raise ValueError("all 100 build numbers for this UTC day are used")
    version = today.strftime("%Y%m%d") + f"{counter:02d}"
    if int(version) > 2_147_483_647:
        raise ValueError("build version exceeds Android's integer versionCode")
    return version


@contextmanager
def config_lock(path):
    lock = path.with_name(path.name + ".lock")
    try:
        stream = lock.open("x", encoding="ascii")
    except FileExistsError:
        raise ValueError(f"another build owns {lock}; remove it only if that build has stopped") from None
    try:
        with stream:
            stream.write(str(os.getpid()))
        yield
    finally:
        lock.unlink()


def save_version(path, before, version):
    if path.read_bytes() != before:
        raise ValueError(f"{path} changed during the build; refusing to overwrite it")
    text = before.decode("utf-8-sig")
    after = re.sub(r"(?m)^(build version:[ \t]*[\"']?)[0-9]{10}",
                   lambda match: match.group(1) + version, text).encode("utf-8")
    if before.startswith(b"\xef\xbb\xbf"):
        after = b"\xef\xbb\xbf" + after
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(after)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def built_apk(repo, variant, version):
    directory = repo / "worktree/TMessagesProj_App/build/outputs/apk" / variant
    metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1 or str(elements[0]["versionCode"]) != version:
        raise ValueError("APK metadata does not contain the requested build version")
    apk = (directory / elements[0]["outputFile"]).resolve()
    if not apk.is_relative_to(directory.resolve()) or not apk.is_file():
        raise ValueError("APK metadata points to a missing or invalid output file")
    return apk


def artifact_path(repo, abi, version):
    properties = (repo / "worktree/gradle.properties").read_text(encoding="utf-8-sig")
    names = re.findall(r"(?m)^APP_VERSION_NAME[ \t]*=[ \t]*([a-zA-Z0-9][a-zA-Z0-9._-]*)[ \t]*\r?$", properties)
    if len(names) != 1:
        raise ValueError("expected one filename-safe APP_VERSION_NAME in gradle.properties")
    architecture = "arm64" if abi == "arm64-v8a" else abi
    return Path(__file__).resolve().parent / "var" / "out" / f"entinygram-private-{architecture}-{names[0]}-{version}.apk"


def run(args):
    repo = args.repo.resolve()
    config = args.config.resolve()
    variant = "debug" if args.build_type == "debug" else "release"
    wrapper = repo / "worktree/gradle/wrapper/gradle-wrapper.jar"
    if not wrapper.is_file() or not (repo / "package.json").is_file():
        raise ValueError("prepare the entinyGram worktree before running this script")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    bun = shutil.which("bun")
    if not args.dry_run and (not java or not Path(java).is_file() or not bun):
        raise ValueError("Java and Bun must be available; check JAVA_HOME and PATH")
    commands = [
        [bun or "bun", "run", "generate-grants"],
        [java or "java", "-Dorg.gradle.appname=gradlew", "-classpath", str(wrapper),
         "org.gradle.wrapper.GradleWrapperMain", "--no-daemon", "-Pinu.pluginless=false",
         f":TMessagesProj_App:assemble{variant.title()}"],
    ]
    if args.dry_run:
        _, previous = read_config(config)
        version = next_version(previous, allow_ahead=True) if getattr(args, "allow_ahead", False) else next_version(previous)
        print(f"{previous} -> {version} (UTC); config unchanged")
        print(f"INU_BUILD={version} INU_BUILD_TYPE={args.build_type} ABI_FILTERS={args.abi}")
        print(f"apply {Path(__file__).with_name('apply.py')} to {repo}")
        for command in commands:
            print(subprocess.list2cmdline(command))
        print(f"APK: {artifact_path(repo, args.abi, version)}")
        return version
    with config_lock(config):
        before, previous = read_config(config)
        version = next_version(previous, allow_ahead=True) if getattr(args, "allow_ahead", False) else next_version(previous)
        output = artifact_path(repo, args.abi, version)
        if output.exists():
            raise ValueError(f"APK already exists: {output}; check build version in {config}")
        env = os.environ.copy()
        env.update(INU_BUILD=version, INU_BUILD_TYPE=args.build_type, ABI_FILTERS=args.abi)
        env.setdefault("CHAQUOPY_BUILD_PYTHON", sys.executable)
        print(f"building {version} ({args.build_type}, {args.abi}, UTC)", flush=True)
        install.run(repo, args.upstream, args.runtime_upstream)
        try:
            subprocess.run(commands[0], cwd=repo, env=env, check=True)
            subprocess.run(commands[1], cwd=repo / "worktree", env=env, check=True)
            apk = built_apk(repo, variant, version)
        finally:
            if not getattr(args, "keep_applied", False):
                try:
                    install.uninstall(repo)
                except Exception as error:
                    print(f"warning: the overlay was not taken out again: {error}", file=sys.stderr)
        output.parent.mkdir(parents=True, exist_ok=True)
        for old in output.parent.glob("entinygram-private-*.apk"):
            old.unlink(missing_ok=True)
        destination = output.open("xb")
        try:
            with destination, apk.open("rb") as source:
                shutil.copyfileobj(source, destination)
        except BaseException:
            output.unlink(missing_ok=True)
            raise
        save_version(config, before, version)
        print(f"built {version}: {output}")
        return version


def main(argv=None):
    bundle = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Apply the private plugin engine and build a numbered local APK.")
    parser.add_argument("--repo", type=Path, default=bundle.parent)
    parser.add_argument("--config", type=Path, default=bundle / "var" / "config.yaml")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--build-type", choices=("release", "beta", "debug"), default="release")
    parser.add_argument("--upstream", type=Path)
    parser.add_argument("--runtime-upstream", type=Path)
    parser.add_argument("--allow-ahead", action="store_true", help="Continue a build number whose date is ahead of today (phone already has a higher versionCode)")
    parser.add_argument("--keep-applied", action="store_true", help="Leave the overlay applied after the build (default: take it out again so src/ and worktree/ stay clean)")
    parser.add_argument("--dry-run", action="store_true", help="Show the next version and commands without changing files or running tools")
    args = parser.parse_args(argv)
    try:
        run(args)
    except (OSError, ValueError, KeyError, TypeError, install.InstallError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
