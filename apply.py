#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid
import xml.etree.ElementTree as ET
from xml.parsers import expat
from dataclasses import dataclass


class InstallError(RuntimeError):
    pass


BUNDLE = Path(__file__).resolve().parent


def load_manifest(bundle):
    single = bundle / "manifest.json"
    if single.is_file():
        return json.loads(single.read_text(encoding="utf-8-sig"))
    parts = sorted((bundle / "manifest").glob("*.json"))
    if not parts:
        raise InstallError(f"no manifest in {bundle}")
    result = {}
    for part in parts:
        for key, value in json.loads(part.read_text(encoding="utf-8-sig")).items():
            if isinstance(value, list):
                result.setdefault(key, []).extend(value)
            elif isinstance(value, dict) and key == "known_drafts":
                result.setdefault(key, {}).update(value)
            elif key in result:
                raise InstallError(f"manifest key {key} repeats in {part.name}")
            else:
                result[key] = value
    return result


def state_dir(bundle, root):
    return bundle / "var" / "state" / digest(str(root).encode())[:20]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def relative(value):
    if not isinstance(value, str) or not value or PureWindowsPath(value).drive:
        raise InstallError(f"invalid relative path: {value!r}")
    value = value.replace("\\", "/")
    parts = value.split("/")
    if value.startswith("/") or any(p in ("", ".", "..") or ":" in p or "\0" in p
                                    or p.casefold() == ".git" for p in parts):
        raise InstallError(f"unsafe relative path: {value!r}")
    return value


def safe_path(root, value):
    path = (root / relative(value)).resolve()
    if not path.is_relative_to(root):
        raise InstallError(f"path escapes {root}: {value}")
    return path


def overlay_file(bundle, value):
    raw = bundle / relative(value)
    if any(part.is_symlink() for part in [raw, *raw.parents] if part.is_relative_to(bundle)):
        raise InstallError(f"overlay sources must be regular files, not links: {value}")
    return read_file(safe_path(bundle, value))


def read_file(path):
    if path.exists() and not path.is_file():
        raise InstallError(f"expected a file: {path}")
    return path.read_bytes() if path.exists() else None


def git(repo, *args):
    command = ["git", "-c", f"safe.directory={repo.resolve()}", "-C", str(repo), *args]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise InstallError(result.stderr.decode("utf-8", "replace").strip())
    return result.stdout


def commit_id(upstream, source):
    commit = source["commit"]
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-fA-F]{7,64}", commit):
        raise InstallError("upstream commit must be a pinned hexadecimal commit id")
    return git(upstream, "rev-parse", "--verify", commit + "^{commit}").decode().strip()


def acquire(source, offline, cache):
    if offline is not None:
        upstream = Path(offline).resolve()
        commit_id(upstream, source)
        return upstream
    url = source["url"]
    if not isinstance(url, str) or not url:
        raise InstallError("upstream URL is missing")
    upstream = cache / (digest(url.encode())[:20] + ".git")
    if not upstream.exists():
        cache.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(["git", "clone", "--bare", "--filter=blob:none", "--no-tags",
                                 "--", url, str(upstream)], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
        if result.returncode:
            raise InstallError(result.stderr.decode("utf-8", "replace").strip())
    try:
        commit_id(upstream, source)
    except InstallError:
        git(upstream, "fetch", "--depth=1", "origin", source["commit"])
        commit_id(upstream, source)
    return upstream


def vendor_files(upstream, source, mappings):
    if not mappings:
        return {}
    normalized = [(relative(m["source"]), relative(m["target"]),
                   [relative(p) for p in m.get("exclude", [])]) for m in mappings]
    archive = git(upstream, "archive", "--format=tar", commit_id(upstream, source), "--",
                  *dict.fromkeys(m[0] for m in normalized))
    result = {}
    matched = set()
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
        for member in tar:
            if member.isdir():
                continue
            name = relative(member.name)
            for index, (prefix, target, excludes) in enumerate(normalized):
                if name != prefix and not name.startswith(prefix + "/"):
                    continue
                suffix = name[len(prefix):].lstrip("/")
                if any(suffix == p or suffix.startswith(p + "/") for p in excludes):
                    continue
                if not member.isfile():
                    raise InstallError(f"upstream member is not a regular file: {name}")
                destination = target + ("/" + suffix if suffix else "")
                if destination in result:
                    raise InstallError(f"duplicate vendor target: {destination}")
                result[destination] = tar.extractfile(member).read()
                matched.add(index)
    for index, (prefix, _, _) in enumerate(normalized):
        if index not in matched:
            raise InstallError(f"upstream source has no included files: {prefix}")
    return result


def newline(data):
    pos = data.find(b"\n")
    return b"\r\n" if pos > 0 and data[pos - 1:pos] == b"\r" else b"\n"


def format_like(data, current):
    if current is None:
        return data
    try:
        text = data.decode("utf-8-sig")
        current.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data
    body = text.replace("\r\n", "\n").encode().replace(b"\n", newline(current))
    return (b"\xef\xbb\xbf" if current.startswith(b"\xef\xbb\xbf") else b"") + body


def occurrences(data, anchor):
    if not isinstance(anchor, str) or not anchor:
        raise InstallError("before, alternatives and after anchors must be nonempty strings")
    raw = anchor.replace("\r\n", "\n").encode("utf-8")
    found = set()
    for candidate in {raw, raw.replace(b"\n", b"\r\n")}:
        start = 0
        while (pos := data.find(candidate, start)) != -1:
            found.add((pos, pos + len(candidate)))
            start = pos + 1
    return found


def edit_bytes(data, edit):
    data.decode("utf-8-sig")
    after = occurrences(data, edit["after"])
    before = set()
    for anchor in [edit["before"], *edit.get("alternatives", [])]:
        before.update(occurrences(data, anchor))
    if len(after) == 1:
        left, right = next(iter(after))
        if all(left <= a and b <= right for a, b in before):
            return data
        raise InstallError(f"both applied and unapplied anchors exist: {edit['path']}")
    if len(after) > 1 or len(before) != 1:
        raise InstallError(f"anchor conflict in {edit['path']}: before={len(before)}, after={len(after)}")
    left, right = next(iter(before))
    replacement = edit["after"].replace("\r\n", "\n").encode().replace(b"\n", newline(data))
    return data[:left] + replacement + data[right:]


def xml_entries(data):
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise InstallError("resource XML must not declare entities or a doctype")
    root = ET.fromstring(data)
    if root.tag != "resources":
        raise InstallError("resource XML root must be resources")
    spans = []
    depth = 0
    start = 0
    closing = 0
    parser = expat.ParserCreate()

    def opened(_name, _attrs):
        nonlocal depth, start
        if depth == 1:
            start = parser.CurrentByteIndex
        depth += 1

    def closed(_name):
        nonlocal depth, closing
        depth -= 1
        pos = parser.CurrentByteIndex
        if depth == 1:
            end = data.index(b">", pos) + 1 if data[pos:pos + 2] == b"</" else pos
            spans.append(data[start:end])
        elif depth == 0:
            closing = pos

    parser.StartElementHandler = opened
    parser.EndElementHandler = closed
    parser.Parse(data, True)

    def normalized(element):
        return (element.tag, tuple(sorted(element.attrib.items())), (element.text or "").strip(),
                tuple((normalized(child), (child.tail or "").strip()) for child in element))

    result = {}
    for child, raw in zip(root, spans, strict=True):
        name = child.get("name")
        if not name or name in result:
            raise InstallError(f"resource XML contains missing or duplicate name: {name}")
        result[name] = (normalized(child), raw)
    return result, closing


def merge_resources(current, payload, target):
    present, closing = xml_entries(current)
    incoming, _ = xml_entries(payload)
    missing = []
    for name, (element, raw) in incoming.items():
        if name in present:
            if present[name][0] != element:
                raise InstallError(f"resource conflict in {target}: {name}")
        else:
            missing.append(raw)
    if not missing:
        return current
    if current[closing:closing + 2] != b"</":
        raise InstallError(f"resource target needs an explicit closing tag: {target}")
    linebreak = newline(current)
    match = re.search(rb"\n([ \t]+)<", current)
    indent = match.group(1) if match else b"    "
    prefix = b"" if current[:closing].endswith((b"\n", b"\r")) else linebreak
    addition = prefix + b"".join(indent + raw.replace(b"\r\n", b"\n").replace(b"\n", linebreak)
                                  + linebreak for raw in missing)
    result = current[:closing] + addition + current[closing:]
    ET.fromstring(result)
    return result


@dataclass
class Change:
    relative: str
    path: Path
    before: bytes | None
    after: bytes


@dataclass
class Plan:
    root: Path
    state: Path
    changes: list[Change]
    installed: dict


def build_plan(repo, bundle, manifest, upstream, runtime_upstream=None):
    root = Path(repo).resolve()
    bundle = Path(bundle).resolve()
    if not root.is_dir():
        raise InstallError(f"repository does not exist: {root}")
    state = state_dir(bundle, root)
    state_file = state / "installed.json"
    installed = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}
    vendored = vendor_files(Path(upstream), manifest["upstream"], manifest.get("vendor", []))
    if manifest.get("runtime_vendor"):
        if runtime_upstream is None:
            raise InstallError("runtime upstream is required")
        runtime = vendor_files(Path(runtime_upstream), manifest["runtime_upstream"], manifest["runtime_vendor"])
        if vendored.keys() & runtime.keys():
            raise InstallError("runtime and engine vendor targets overlap")
        vendored.update(runtime)
    desired = dict(vendored)
    allowed = {relative(p): set(hashes) for p, hashes in manifest.get("known_drafts", {}).items()}
    for edit in manifest.get("vendor_edits", []):
        target = relative(edit["path"])
        if target not in desired:
            raise InstallError(f"vendor edit has no source: {target}")
        desired[target] = edit_bytes(desired[target], edit)
    for entry in manifest.get("files", []):
        target = relative(entry["target"])
        payload = overlay_file(bundle, entry["source"])
        if payload is None:
            raise InstallError(f"payload is missing: {entry['source']}")
        desired[target] = payload
        allowed.setdefault(target, set()).update(entry.get("previous_sha256", []))
    changes = {}
    for target, content in desired.items():
        path = safe_path(root, target)
        current = read_file(path)
        content = format_like(content, current)
        original = format_like(vendored.get(target, content), current)
        previous = allowed.get(target, set()) | {installed.get(target)}
        if current is not None and current not in (content, original) and digest(current) not in previous:
            raise InstallError(f"unknown existing content: {target}")
        if path in changes:
            raise InstallError(f"targets resolve to the same file: {target}")
        changes[path] = Change(target, path, current, content)
    for edit in manifest.get("edits", []):
        target = relative(edit["path"])
        path = safe_path(root, target)
        if target in desired:
            raise InstallError(f"use vendor_edits or payload for managed file: {target}")
        if path not in changes:
            current = read_file(path)
            if current is None:
                raise InstallError(f"edit target is missing: {target}")
            changes[path] = Change(target, path, current, current)
        changes[path].after = edit_bytes(changes[path].after, edit)
    for entry in manifest.get("resources", []):
        target = relative(entry["target"])
        path = safe_path(root, target)
        if target in desired:
            raise InstallError(f"resource target overlaps managed file: {target}")
        if path not in changes:
            current = read_file(path)
            if current is None:
                raise InstallError(f"resource target is missing: {target}")
            changes[path] = Change(target, path, current, current)
        payload = overlay_file(bundle, entry["source"])
        if payload is None:
            raise InstallError(f"resource payload is missing: {entry['source']}")
        changes[path].after = merge_resources(changes[path].after, payload, target)
    installed.update({c.relative: digest(c.after) for c in changes.values()})
    return Plan(root, state, sorted((c for c in changes.values() if c.before != c.after),
                                    key=lambda c: c.relative), installed)


def atomic_write(path, data):
    handle, temporary = tempfile.mkstemp(prefix=".plugin-overlay-", dir=path.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if path.exists():
            os.chmod(temporary, path.stat().st_mode)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def apply_plan(plan):
    if not plan.changes:
        return None
    for change in plan.changes:
        if safe_path(plan.root, change.relative) != change.path or read_file(change.path) != change.before:
            raise InstallError(f"file changed after preflight: {change.relative}")
    backup = plan.state / "backups" / uuid.uuid4().hex
    backup.mkdir(parents=True)
    records = []
    for index, change in enumerate(plan.changes):
        name = str(index) + ".bin" if change.before is not None else None
        if name:
            (backup / name).write_bytes(change.before)
        records.append({"path": change.relative, "backup": name, "after_sha256": digest(change.after)})
    (backup / "transaction.json").write_text(json.dumps({"repo": str(plan.root), "files": records},
                                                      indent=2), encoding="utf-8")
    touched = []
    directories = []
    try:
        for change in plan.changes:
            if safe_path(plan.root, change.relative) != change.path or read_file(change.path) != change.before:
                raise InstallError(f"file changed during apply: {change.relative}")
            missing = []
            parent = change.path.parent
            while not parent.exists():
                missing.append(parent)
                parent = parent.parent
            for directory in reversed(missing):
                directory.mkdir()
                directories.append(directory)
            touched.append(change)
            atomic_write(change.path, change.after)
        atomic_write(plan.state / "installed.json", json.dumps(plan.installed, indent=2).encode())
    except BaseException as error:
        failures = []
        for change in reversed(touched):
            try:
                current = read_file(change.path)
                if current == change.before:
                    continue
                if current != change.after:
                    raise InstallError("another process changed the file; preserved its content")
                if change.before is None:
                    change.path.unlink()
                else:
                    atomic_write(change.path, change.before)
            except BaseException as rollback_error:
                failures.append(f"{change.relative}: {rollback_error}")
        for directory in reversed(directories):
            try:
                directory.rmdir()
            except OSError:
                pass
        detail = "; rollback incomplete: " + "; ".join(failures) if failures else "; target changes rolled back"
        raise InstallError(f"apply failed: {error}{detail}; backups: {backup}") from error
    return backup


def revert_edit(data, edit):
    data.decode("utf-8-sig")
    after = occurrences(data, edit["after"])
    before = set()
    for anchor in [edit["before"], *edit.get("alternatives", [])]:
        before.update(occurrences(data, anchor))
    if not after:
        if before:
            return data
        raise InstallError(f"neither the applied nor the original anchor exists in {edit['path']}")
    if len(after) > 1:
        raise InstallError(f"anchor conflict in {edit['path']}: after={len(after)}")
    left, right = next(iter(after))
    replacement = edit["before"].replace("\r\n", "\n").encode().replace(b"\n", newline(data))
    return data[:left] + replacement + data[right:]


def head_file(root, target):
    result = subprocess.run(["git", "-C", str(root), "show", f"HEAD:{target}"], capture_output=True)
    return result.stdout if result.returncode == 0 else None


def revert_resources(current, payload, head, target):
    if head is None:
        raise InstallError(f"cannot tell which resources the overlay added, file is not in HEAD: {target}")
    present, _ = xml_entries(current)
    incoming, _ = xml_entries(payload)
    original, _ = xml_entries(head)
    result = current
    for name, (_element, raw) in incoming.items():
        if name in original or name not in present:
            continue
        raw = present[name][1]
        start = result.find(raw)
        if start == -1 or result.find(raw, start + 1) != -1:
            raise InstallError(f"resource {name} is not unique in {target}")
        end = start + len(raw)
        while start > 0 and result[start - 1:start] in (b" ", b"\t"):
            start -= 1
        if result[end:end + 2] == b"\r\n":
            end += 2
        elif result[end:end + 1] == b"\n":
            end += 1
        result = result[:start] + result[end:]
    ET.fromstring(result)
    return result


def build_unplan(repo, bundle, manifest):
    root = Path(repo).resolve()
    bundle = Path(bundle).resolve()
    state = state_dir(bundle, root)
    state_file = state / "installed.json"
    installed = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}
    changes = {}

    def entry(target):
        path = safe_path(root, target)
        if path not in changes:
            current = read_file(path)
            if current is None:
                raise InstallError(f"overlay target is missing: {target}")
            changes[path] = Change(target, path, current, current)
        return changes[path]

    for item in manifest.get("files", []):
        target = relative(item["target"])
        if target.startswith("extera-plugins/"):
            continue
        path = safe_path(root, target)
        current = read_file(path)
        if current is None:
            continue
        payload = overlay_file(bundle, item["source"])
        known = {digest(payload)} | set(item.get("previous_sha256", [])) | {installed.get(target)}
        if current != format_like(payload, current) and digest(current) not in known:
            raise InstallError(f"{target} changed after the overlay wrote it; remove it by hand")
        changes[path] = Change(target, path, current, None)
    for edit in reversed(manifest.get("edits", [])):
        change = entry(relative(edit["path"]))
        if change.after is None:
            raise InstallError(f"edit target is also a payload file: {edit['path']}")
        change.after = revert_edit(change.after, edit)
    for item in manifest.get("resources", []):
        target = relative(item["target"])
        change = entry(target)
        payload = overlay_file(bundle, item["source"])
        change.after = revert_resources(change.after, payload, head_file(root, target), target)
    return root, state, [c for c in sorted(changes.values(), key=lambda c: c.relative) if c.before != c.after]


def remove_fork_links(root, deleted):
    res = root / "src" / "res"
    for path in deleted:
        try:
            sub = path.relative_to(res)
        except ValueError:
            continue
        link = root / "worktree" / "TMessagesProj" / "src" / "main" / "res" / sub
        if link.is_symlink() and not link.exists():
            link.unlink()


def uninstall(repo, bundle=None, check=False):
    bundle = Path(bundle or BUNDLE).resolve()
    manifest = load_manifest(bundle)
    root, state, changes = build_unplan(repo, bundle, manifest)
    for change in changes:
        print(("remove " if change.after is None else "revert ") + change.relative)
    if check or not changes:
        print(f"uninstall preflight passed: {len(changes)} file(s)" + ("; target unchanged" if check else ""))
        if not check:
            (state / "installed.json").unlink(missing_ok=True)
        return changes
    backup = state / "backups" / ("uninstall-" + uuid.uuid4().hex)
    backup.mkdir(parents=True)
    records = []
    for index, change in enumerate(changes):
        (backup / f"{index}.bin").write_bytes(change.before)
        records.append({"path": change.relative, "backup": f"{index}.bin"})
    (backup / "transaction.json").write_text(json.dumps({"repo": str(root), "files": records}, indent=2),
                                             encoding="utf-8")
    deleted = []
    for change in changes:
        if read_file(change.path) != change.before:
            raise InstallError(f"file changed during uninstall: {change.relative}")
    for change in changes:
        if change.after is None:
            change.path.unlink()
            deleted.append(change.path)
            parent = change.path.parent
            while parent != root:
                try:
                    parent.rmdir()
                except OSError:
                    break
                parent = parent.parent
        else:
            atomic_write(change.path, change.after)
    remove_fork_links(root, deleted)
    (state / "installed.json").unlink(missing_ok=True)
    print(f"uninstalled: {len(changes)} file(s); backups: {backup}", flush=True)
    return changes


def link_fork_resources(repo):
    bun = shutil.which("bun")
    lib = repo / "assets" / "scripts" / "lib.ts"
    worktree = repo / "worktree"
    if not bun or not lib.is_file() or not worktree.is_dir():
        return
    code = "import { linkForkSource } from './assets/scripts/lib.ts'; await linkForkSource(Bun.env.ENTINY_LINK_TARGET, false)"
    subprocess.run([bun, "-e", code], cwd=repo, env={**os.environ, "ENTINY_LINK_TARGET": str(worktree)}, check=True)


def run(repo, upstream=None, runtime_upstream=None, check=False, bundle=None):
    bundle = Path(bundle or BUNDLE).resolve()
    manifest = load_manifest(bundle)
    with tempfile.TemporaryDirectory(prefix="entiny-plugin-check-") as temporary:
        cache = Path(temporary) if check else bundle / "var" / "cache"
        engine = acquire(manifest["upstream"], upstream, cache)
        runtime = None
        if manifest.get("runtime_vendor"):
            runtime = acquire(manifest["runtime_upstream"], runtime_upstream, cache)
        plan = build_plan(repo, bundle, manifest, engine, runtime)
        for change in plan.changes:
            print(("add " if change.before is None else "edit ") + change.relative)
        if check:
            print(f"preflight passed: {len(plan.changes)} file(s); target unchanged")
        else:
            backup = apply_plan(plan)
            print(f"applied: {len(plan.changes)} file(s)" + (f"; backups: {backup}" if backup else " (no changes)"), flush=True)
            link_fork_resources(Path(repo))
        return plan


def status(repo, upstream=None, runtime_upstream=None, bundle=None):
    bundle = Path(bundle or BUNDLE).resolve()
    manifest = load_manifest(bundle)
    with tempfile.TemporaryDirectory(prefix="entiny-plugin-status-") as temporary:
        cache = bundle / "var" / "cache" if (bundle / "var" / "cache").is_dir() else Path(temporary)
        engine = acquire(manifest["upstream"], upstream, cache)
        runtime = acquire(manifest["runtime_upstream"], runtime_upstream, cache) if manifest.get("runtime_vendor") else None
        plan = build_plan(repo, bundle, manifest, engine, runtime)
    total = len(plan.installed)
    pending = len(plan.changes)
    state = "applied" if not pending else "not applied" if pending == total else "partly applied"
    host = [c.relative for c in plan.changes if not c.relative.startswith("extera-plugins/")]
    print(f"{state}: {total - pending}/{total} file(s) in place, {len(host)} host file(s) pending")
    for name in host:
        print("  pending " + name)
    return state


def hook_map(bundle=None):
    bundle = Path(bundle or BUNDLE).resolve()
    rows = {}
    for part in sorted((bundle / "manifest").glob("edits-*.json")):
        area = part.stem[len("edits-"):]
        for edit in json.loads(part.read_text(encoding="utf-8-sig"))["edits"]:
            row = rows.setdefault(edit["path"], [area, 0, 0])
            row[1] += 1
            row[2] += edit["after"].count("\n") - edit["before"].count("\n")
    lines = ["| area | file | hunks | net lines |", "| --- | --- | --- | --- |"]
    for path, (area, hunks, net) in sorted(rows.items(), key=lambda r: (r[1][0], r[0])):
        lines.append(f"| {area} | `{path}` | {hunks} | {net:+d} |")
    print("\n".join(lines))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Apply or revert the private entinyGram Python plugin overlay.")
    parser.add_argument("--repo", type=Path, default=BUNDLE.parent)
    parser.add_argument("--upstream", type=Path, help="Existing engine git checkout or bare repository (offline)")
    parser.add_argument("--runtime-upstream", type=Path, help="Existing Chaquopy git repository (offline)")
    parser.add_argument("--check", action="store_true", help="Full preflight without changing target files")
    parser.add_argument("--revert", "--uninstall", dest="revert", action="store_true",
                        help="Take the overlay out of the target again; combine with --check for a dry run")
    parser.add_argument("--status", action="store_true", help="Report whether the overlay is applied, without changes")
    parser.add_argument("--map", action="store_true", help="Print the host hook registry as a markdown table")
    args = parser.parse_args(argv)
    try:
        if args.map:
            hook_map()
            return 0
        if args.status:
            status(args.repo, args.upstream, args.runtime_upstream)
            return 0
        if args.revert:
            uninstall(args.repo, check=args.check)
            return 0
        run(args.repo, args.upstream, args.runtime_upstream, args.check)
    except (InstallError, OSError, ValueError, KeyError, TypeError, ET.ParseError, expat.ExpatError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
