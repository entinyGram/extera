import { createHash, randomUUID } from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { spawnSync } from "node:child_process";
import { resourceEntries } from "./xml.ts";

export class InstallError extends Error {}

export const BUNDLE = path.resolve(import.meta.dir, "..");
const win = process.platform === "win32";
const fatal = new TextDecoder("utf-8", { fatal: true });
const BOM = Buffer.from([0xef, 0xbb, 0xbf]);

export type Source = { url: string; commit: string };
export type Edit = { path: string; before: string; after: string; alternatives?: string[] };
export type Mapping = { source: string; target: string; exclude?: string[] };
export type FileEntry = { source: string; target: string; previous_sha256?: string[] };
export type Manifest = {
  upstream: Source;
  runtime_upstream?: Source;
  host?: Record<string, string>;
  vendor?: Mapping[];
  runtime_vendor?: Mapping[];
  vendor_edits?: Edit[];
  known_drafts?: Record<string, string[]>;
  files?: FileEntry[];
  resources?: FileEntry[];
  edits?: Edit[];
};
export type Change = { relative: string; path: string; before: Buffer | null; after: Buffer | null };
export type Plan = { root: string; state: string; changes: Change[]; installed: Record<string, string> };

export const fsio = { atomicWrite };

export function digest(data: Buffer | string) {
  return createHash("sha256").update(data).digest("hex");
}

export function relative(value: unknown): string {
  if (typeof value !== "string" || !value || /^[a-zA-Z]:/.test(value)) {
    throw new InstallError(`invalid relative path: ${JSON.stringify(value)}`);
  }
  const normalized = value.replaceAll("\\", "/");
  const parts = normalized.split("/");
  if (normalized.startsWith("/") || parts.some(p => ["", ".", ".."].includes(p) || p.includes(":")
      || p.includes("\0") || p.toLowerCase() === ".git")) {
    throw new InstallError(`unsafe relative path: ${JSON.stringify(value)}`);
  }
  return normalized;
}

function realish(target: string) {
  let existing = target;
  const rest: string[] = [];
  while (!fs.existsSync(existing)) {
    const parent = path.dirname(existing);
    if (parent === existing) break;
    rest.unshift(path.basename(existing));
    existing = parent;
  }
  return path.join(fs.realpathSync.native(existing), ...rest);
}

function inside(child: string, root: string) {
  const r = path.relative(win ? root.toLowerCase() : root, win ? child.toLowerCase() : child);
  return r === "" || (!r.startsWith("..") && !path.isAbsolute(r));
}

export function safePath(root: string, value: string) {
  const resolved = realish(path.join(root, relative(value)));
  if (!inside(resolved, root)) throw new InstallError(`path escapes ${root}: ${value}`);
  return resolved;
}

export function readFile(file: string): Buffer | null {
  if (!fs.existsSync(file)) return null;
  if (!fs.statSync(file).isFile()) throw new InstallError(`expected a file: ${file}`);
  return fs.readFileSync(file);
}

export function overlayFile(bundle: string, value: string) {
  const raw = path.join(bundle, relative(value));
  for (let p = raw; inside(p, bundle) && p !== bundle; p = path.dirname(p)) {
    if (fs.existsSync(p) && fs.lstatSync(p).isSymbolicLink()) {
      throw new InstallError(`overlay sources must be regular files, not links: ${value}`);
    }
  }
  return readFile(safePath(bundle, value));
}

export function git(repo: string, args: string[], input?: Buffer) {
  const result = spawnSync("git", ["-c", `safe.directory=${path.resolve(repo).replaceAll("\\", "/")}`, "-C", repo, ...args],
    { input, maxBuffer: 1 << 30 });
  if (result.error) throw new InstallError(String(result.error));
  if (result.status) throw new InstallError(result.stderr.toString("utf-8").trim());
  return result.stdout as Buffer;
}

export function commitId(upstream: string, source: Source) {
  if (typeof source.commit !== "string" || !/^[0-9a-fA-F]{7,64}$/.test(source.commit)) {
    throw new InstallError("upstream commit must be a pinned hexadecimal commit id");
  }
  return git(upstream, ["rev-parse", "--verify", source.commit + "^{commit}"]).toString().trim();
}

export function acquire(source: Source, offline: string | undefined, cache: string) {
  if (offline) {
    const upstream = path.resolve(offline);
    commitId(upstream, source);
    return upstream;
  }
  if (typeof source.url !== "string" || !source.url) throw new InstallError("upstream URL is missing");
  const upstream = path.join(cache, digest(source.url).slice(0, 20) + ".git");
  if (!fs.existsSync(upstream)) {
    fs.mkdirSync(cache, { recursive: true });
    const result = spawnSync("git", ["clone", "--bare", "--filter=blob:none", "--no-tags", "--", source.url, upstream]);
    if (result.status) throw new InstallError(result.stderr.toString("utf-8").trim());
  }
  try {
    commitId(upstream, source);
  } catch {
    git(upstream, ["fetch", "--depth=1", "origin", source.commit]);
    commitId(upstream, source);
  }
  return upstream;
}

function blobs(upstream: string, ids: string[]) {
  const out = git(upstream, ["cat-file", "--batch"], Buffer.from(ids.map(id => id + "\n").join("")));
  const result: Buffer[] = [];
  let pos = 0;
  for (const id of ids) {
    const eol = out.indexOf(0x0a, pos);
    const [sha, type, size] = out.subarray(pos, eol).toString().split(" ");
    if (sha !== id || type !== "blob") throw new InstallError(`cannot read upstream object ${id}`);
    const start = eol + 1;
    result.push(Buffer.from(out.subarray(start, start + Number(size))));
    pos = start + Number(size) + 1;
  }
  return result;
}

export function vendorFiles(upstream: string, source: Source, mappings: Mapping[] = []) {
  const result = new Map<string, Buffer>();
  if (!mappings.length) return result;
  const normalized = mappings.map(m => [relative(m.source), relative(m.target), (m.exclude ?? []).map(relative)] as const);
  const commit = commitId(upstream, source);
  const listing = git(upstream, ["ls-tree", "-r", "-z", "--full-tree", commit, "--", ...new Set(normalized.map(m => m[0]))])
    .toString("utf-8").split("\0").filter(Boolean);
  const wanted: [string, string][] = [];
  const matched = new Set<number>();
  for (const line of listing) {
    const tab = line.indexOf("\t");
    const [mode, type, id] = line.slice(0, tab).split(" ");
    const name = relative(line.slice(tab + 1));
    if (type === "commit") continue;
    normalized.forEach(([prefix, target, excludes], index) => {
      if (name !== prefix && !name.startsWith(prefix + "/")) return;
      const suffix = name.slice(prefix.length).replace(/^\/+/, "");
      if (excludes.some(p => suffix === p || suffix.startsWith(p + "/"))) return;
      if (type !== "blob" || mode === "120000") throw new InstallError(`upstream member is not a regular file: ${name}`);
      const destination = target + (suffix ? "/" + suffix : "");
      if (wanted.some(w => w[0] === destination)) throw new InstallError(`duplicate vendor target: ${destination}`);
      wanted.push([destination, id]);
      matched.add(index);
    });
  }
  normalized.forEach(([prefix], index) => {
    if (!matched.has(index)) throw new InstallError(`upstream source has no included files: ${prefix}`);
  });
  const contents = blobs(upstream, wanted.map(w => w[1]));
  wanted.forEach(([destination], i) => result.set(destination, contents[i]));
  return result;
}

export function newline(data: Buffer) {
  const pos = data.indexOf(0x0a);
  return pos > 0 && data[pos - 1] === 0x0d ? "\r\n" : "\n";
}

function decode(data: Buffer) {
  const text = fatal.decode(data);
  return text.startsWith("﻿") ? text.slice(1) : text;
}

export function formatLike(data: Buffer, current: Buffer | null) {
  if (current === null) return data;
  let text: string;
  try {
    text = decode(data);
    decode(current);
  } catch {
    return data;
  }
  const body = Buffer.from(text.replaceAll("\r\n", "\n").replaceAll("\n", newline(current)), "utf-8");
  return current.subarray(0, 3).equals(BOM) ? Buffer.concat([BOM, body]) : body;
}

function occurrences(data: Buffer, anchor: unknown) {
  if (typeof anchor !== "string" || !anchor) throw new InstallError("before, alternatives and after anchors must be nonempty strings");
  const raw = anchor.replaceAll("\r\n", "\n");
  const found = new Map<string, [number, number]>();
  for (const candidate of new Set([raw, raw.replaceAll("\n", "\r\n")])) {
    const needle = Buffer.from(candidate, "utf-8");
    for (let pos = data.indexOf(needle); pos !== -1; pos = data.indexOf(needle, pos + 1)) {
      found.set(`${pos}:${pos + needle.length}`, [pos, pos + needle.length]);
    }
  }
  return [...found.values()];
}

function originals(data: Buffer, edit: Edit) {
  const before = new Map<string, [number, number]>();
  for (const anchor of [edit.before, ...(edit.alternatives ?? [])]) {
    for (const span of occurrences(data, anchor)) before.set(span.join(":"), span);
  }
  return [...before.values()];
}

function replacement(text: string, data: Buffer) {
  return Buffer.from(text.replaceAll("\r\n", "\n").replaceAll("\n", newline(data)), "utf-8");
}

export function editBytes(data: Buffer, edit: Edit) {
  fatal.decode(data);
  const after = occurrences(data, edit.after);
  const before = originals(data, edit);
  if (after.length === 1) {
    const [left, right] = after[0];
    if (before.every(([a, b]) => left <= a && b <= right)) return data;
    throw new InstallError(`both applied and unapplied anchors exist: ${edit.path}`);
  }
  if (after.length > 1 || before.length !== 1) {
    throw new InstallError(`anchor conflict in ${edit.path}: before=${before.length}, after=${after.length}`);
  }
  const [left, right] = before[0];
  return Buffer.concat([data.subarray(0, left), replacement(edit.after, data), data.subarray(right)]);
}

export function revertEdit(data: Buffer, edit: Edit) {
  fatal.decode(data);
  const after = occurrences(data, edit.after);
  if (!after.length) {
    if (originals(data, edit).length) return data;
    throw new InstallError(`neither the applied nor the original anchor exists in ${edit.path}`);
  }
  if (after.length > 1) throw new InstallError(`anchor conflict in ${edit.path}: after=${after.length}`);
  const [left, right] = after[0];
  return Buffer.concat([data.subarray(0, left), replacement(edit.before, data), data.subarray(right)]);
}

export function mergeResources(current: Buffer, payload: Buffer, target: string) {
  const present = resourceEntries(current);
  const incoming = resourceEntries(payload);
  const missing: Buffer[] = [];
  for (const [name, entry] of incoming.entries) {
    const have = present.entries.get(name);
    if (have) {
      if (have.key !== entry.key) throw new InstallError(`resource conflict in ${target}: ${name}`);
    } else missing.push(entry.raw);
  }
  if (!missing.length) return current;
  const closing = present.closing;
  if (current[closing] !== 0x3c || current[closing + 1] !== 0x2f) {
    throw new InstallError(`resource target needs an explicit closing tag: ${target}`);
  }
  const linebreak = newline(current);
  const indent = /\n([ \t]+)</.exec(current.toString("latin1"))?.[1] ?? "    ";
  const head = current.subarray(0, closing);
  const prefix = head.length && (head[head.length - 1] === 0x0a || head[head.length - 1] === 0x0d) ? "" : linebreak;
  const addition = Buffer.concat([Buffer.from(prefix), ...missing.map(raw => Buffer.concat([
    Buffer.from(indent, "latin1"), replacement(raw.toString("utf-8"), Buffer.from(linebreak)), Buffer.from(linebreak)]))]);
  const result = Buffer.concat([head, addition, current.subarray(closing)]);
  resourceEntries(result);
  return result;
}

export function revertResources(current: Buffer, payload: Buffer, head: Buffer | null, target: string) {
  if (head === null) throw new InstallError(`cannot tell which resources the overlay added, file is not in HEAD: ${target}`);
  const present = resourceEntries(current).entries;
  const original = resourceEntries(head).entries;
  let result = current;
  for (const name of resourceEntries(payload).entries.keys()) {
    if (original.has(name) || !present.has(name)) continue;
    const raw = present.get(name)!.raw;
    let start = result.indexOf(raw);
    if (start === -1 || result.indexOf(raw, start + 1) !== -1) throw new InstallError(`resource ${name} is not unique in ${target}`);
    let end = start + raw.length;
    while (start > 0 && (result[start - 1] === 0x20 || result[start - 1] === 0x09)) start--;
    if (result[end] === 0x0d && result[end + 1] === 0x0a) end += 2;
    else if (result[end] === 0x0a) end += 1;
    result = Buffer.concat([result.subarray(0, start), result.subarray(end)]);
  }
  resourceEntries(result);
  return result;
}

export function loadManifest(bundle: string): Manifest {
  const single = path.join(bundle, "manifest.json");
  if (fs.existsSync(single)) return JSON.parse(decode(fs.readFileSync(single)));
  const dir = path.join(bundle, "manifest");
  const parts = fs.existsSync(dir) ? fs.readdirSync(dir).filter(n => n.endsWith(".json")).sort() : [];
  if (!parts.length) throw new InstallError(`no manifest in ${bundle}`);
  const result: Record<string, any> = {};
  for (const part of parts) {
    for (const [key, value] of Object.entries(JSON.parse(decode(fs.readFileSync(path.join(dir, part)))))) {
      if (Array.isArray(value)) (result[key] ??= []).push(...value);
      else if (key === "known_drafts" && value && typeof value === "object") Object.assign(result[key] ??= {}, value);
      else if (key in result) throw new InstallError(`manifest key ${key} repeats in ${part}`);
      else result[key] = value;
    }
  }
  return result as Manifest;
}

export function stateDir(bundle: string, root: string) {
  return path.join(bundle, "var", "state", digest(root).slice(0, 20));
}

function readInstalled(state: string): Record<string, string> {
  const file = path.join(state, "installed.json");
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf-8")) : {};
}

function rootOf(repo: string) {
  const root = path.resolve(repo);
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) throw new InstallError(`repository does not exist: ${root}`);
  return fs.realpathSync.native(root);
}

function managed(bundle: string, manifest: Manifest, upstream: string, runtimeUpstream?: string) {
  const vendored = vendorFiles(upstream, manifest.upstream, manifest.vendor);
  if (manifest.runtime_vendor?.length) {
    if (!runtimeUpstream) throw new InstallError("runtime upstream is required");
    const runtime = vendorFiles(runtimeUpstream, manifest.runtime_upstream!, manifest.runtime_vendor);
    for (const [key, value] of runtime) {
      if (vendored.has(key)) throw new InstallError("runtime and engine vendor targets overlap");
      vendored.set(key, value);
    }
  }
  const desired = new Map(vendored);
  const allowed = new Map<string, Set<string>>();
  for (const [p, hashes] of Object.entries(manifest.known_drafts ?? {})) allowed.set(relative(p), new Set(hashes));
  for (const edit of manifest.vendor_edits ?? []) {
    const target = relative(edit.path);
    if (!desired.has(target)) throw new InstallError(`vendor edit has no source: ${target}`);
    desired.set(target, editBytes(desired.get(target)!, edit));
  }
  for (const entry of manifest.files ?? []) {
    const target = relative(entry.target);
    const payload = overlayFile(bundle, entry.source);
    if (payload === null) throw new InstallError(`payload is missing: ${entry.source}`);
    desired.set(target, payload);
    const set = allowed.get(target) ?? new Set();
    for (const h of entry.previous_sha256 ?? []) set.add(h);
    allowed.set(target, set);
  }
  return { vendored, desired, allowed };
}

export function buildPlan(repo: string, bundle: string, manifest: Manifest, upstream: string, runtimeUpstream?: string): Plan {
  const root = rootOf(repo);
  bundle = path.resolve(bundle);
  const state = stateDir(bundle, root);
  const installed = readInstalled(state);
  const { vendored, desired, allowed } = managed(bundle, manifest, upstream, runtimeUpstream);
  const changes = new Map<string, Change>();
  for (const [target, wanted] of desired) {
    const file = safePath(root, target);
    const current = readFile(file);
    const content = formatLike(wanted, current);
    const original = formatLike(vendored.get(target) ?? wanted, current);
    const previous = new Set(allowed.get(target) ?? []);
    if (installed[target]) previous.add(installed[target]);
    if (current !== null && !current.equals(content) && !current.equals(original) && !previous.has(digest(current))) {
      throw new InstallError(`unknown existing content: ${target}`);
    }
    const key = win ? file.toLowerCase() : file;
    if (changes.has(key)) throw new InstallError(`targets resolve to the same file: ${target}`);
    changes.set(key, { relative: target, path: file, before: current, after: content });
  }
  const existing = (target: string, missing: string) => {
    const file = safePath(root, target);
    const key = win ? file.toLowerCase() : file;
    if (!changes.has(key)) {
      const current = readFile(file);
      if (current === null) throw new InstallError(`${missing}: ${target}`);
      changes.set(key, { relative: target, path: file, before: current, after: current });
    }
    return changes.get(key)!;
  };
  for (const edit of manifest.edits ?? []) {
    const target = relative(edit.path);
    if (desired.has(target)) throw new InstallError(`use vendor_edits or payload for managed file: ${target}`);
    const change = existing(target, "edit target is missing");
    change.after = editBytes(change.after!, edit);
  }
  for (const entry of manifest.resources ?? []) {
    const target = relative(entry.target);
    if (desired.has(target)) throw new InstallError(`resource target overlaps managed file: ${target}`);
    const change = existing(target, "resource target is missing");
    const payload = overlayFile(bundle, entry.source);
    if (payload === null) throw new InstallError(`resource payload is missing: ${entry.source}`);
    change.after = mergeResources(change.after!, payload, target);
  }
  for (const c of changes.values()) installed[c.relative] = digest(c.after!);
  const pending = [...changes.values()].filter(c => c.before === null || !c.before.equals(c.after!))
    .sort((a, b) => (a.relative < b.relative ? -1 : a.relative > b.relative ? 1 : 0));
  return { root, state, changes: pending, installed };
}

export function atomicWrite(file: string, data: Buffer | string) {
  const temporary = path.join(path.dirname(file), `.plugin-overlay-${randomUUID()}`);
  try {
    const fd = fs.openSync(temporary, "wx");
    try {
      fs.writeSync(fd, typeof data === "string" ? Buffer.from(data) : data);
      fs.fsyncSync(fd);
    } finally {
      fs.closeSync(fd);
    }
    if (fs.existsSync(file)) fs.chmodSync(temporary, fs.statSync(file).mode);
    fs.renameSync(temporary, file);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}

function same(a: Buffer | null, b: Buffer | null) {
  return a === null ? b === null : b !== null && a.equals(b);
}

export function applyPlan(plan: Plan) {
  if (!plan.changes.length) return null;
  for (const change of plan.changes) {
    if (safePath(plan.root, change.relative) !== change.path || !same(readFile(change.path), change.before)) {
      throw new InstallError(`file changed after preflight: ${change.relative}`);
    }
  }
  const backup = path.join(plan.state, "backups", randomUUID().replaceAll("-", ""));
  fs.mkdirSync(backup, { recursive: true });
  const records = plan.changes.map((change, index) => {
    const name = change.before !== null ? `${index}.bin` : null;
    if (name) fs.writeFileSync(path.join(backup, name), change.before!);
    return { path: change.relative, backup: name, after_sha256: digest(change.after!) };
  });
  fs.writeFileSync(path.join(backup, "transaction.json"), JSON.stringify({ repo: plan.root, files: records }, null, 2));
  const touched: Change[] = [];
  const directories: string[] = [];
  try {
    for (const change of plan.changes) {
      if (safePath(plan.root, change.relative) !== change.path || !same(readFile(change.path), change.before)) {
        throw new InstallError(`file changed during apply: ${change.relative}`);
      }
      const missing: string[] = [];
      for (let parent = path.dirname(change.path); !fs.existsSync(parent); parent = path.dirname(parent)) missing.push(parent);
      for (const directory of missing.reverse()) {
        fs.mkdirSync(directory);
        directories.push(directory);
      }
      touched.push(change);
      fsio.atomicWrite(change.path, change.after!);
    }
    fsio.atomicWrite(path.join(plan.state, "installed.json"), JSON.stringify(plan.installed, null, 2));
  } catch (error) {
    const failures: string[] = [];
    for (const change of touched.reverse()) {
      try {
        const current = readFile(change.path);
        if (same(current, change.before)) continue;
        if (!same(current, change.after)) throw new InstallError("another process changed the file; preserved its content");
        if (change.before === null) fs.unlinkSync(change.path);
        else atomicWrite(change.path, change.before);
      } catch (rollback) {
        failures.push(`${change.relative}: ${(rollback as Error).message}`);
      }
    }
    for (const directory of directories.reverse()) {
      try { fs.rmdirSync(directory); } catch {}
    }
    const detail = failures.length ? "; rollback incomplete: " + failures.join("; ") : "; target changes rolled back";
    throw new InstallError(`apply failed: ${(error as Error).message}${detail}; backups: ${backup}`);
  }
  return backup;
}

function headFile(root: string, target: string) {
  const result = spawnSync("git", ["-C", root, "show", `HEAD:${target}`], { maxBuffer: 1 << 30 });
  return result.status === 0 ? (result.stdout as Buffer) : null;
}

export function buildUnplan(repo: string, bundle: string, manifest: Manifest, upstream: string, runtimeUpstream?: string) {
  const root = rootOf(repo);
  bundle = path.resolve(bundle);
  const state = stateDir(bundle, root);
  const installed = readInstalled(state);
  const { vendored, desired, allowed } = managed(bundle, manifest, upstream, runtimeUpstream);
  const changes = new Map<string, Change>();
  const entry = (target: string) => {
    const file = safePath(root, target);
    if (!changes.has(file)) {
      const current = readFile(file);
      if (current === null) throw new InstallError(`overlay target is missing: ${target}`);
      changes.set(file, { relative: target, path: file, before: current, after: current });
    }
    return changes.get(file)!;
  };
  for (const [target, wanted] of desired) {
    if (target.startsWith("extera-plugins/")) continue;
    const file = safePath(root, target);
    const current = readFile(file);
    if (current === null) continue;
    const head = headFile(root, target);
    const restored = head === null ? null : formatLike(head, current);
    const known = new Set([...(allowed.get(target) ?? []), installed[target]]);
    const ours = [wanted, vendored.get(target)].some(c => c && current.equals(formatLike(c, current)));
    if (!ours && !known.has(digest(current)) && !same(current, restored)) {
      throw new InstallError(`${target} changed after the overlay wrote it; remove it by hand`);
    }
    changes.set(file, { relative: target, path: file, before: current, after: restored });
  }
  for (const edit of [...(manifest.edits ?? [])].reverse()) {
    const change = entry(relative(edit.path));
    if (change.after === null) throw new InstallError(`edit target is also a payload file: ${edit.path}`);
    change.after = revertEdit(change.after, edit);
  }
  for (const item of manifest.resources ?? []) {
    const target = relative(item.target);
    const change = entry(target);
    change.after = revertResources(change.after!, overlayFile(bundle, item.source)!, headFile(root, target), target);
  }
  const pending = [...changes.values()].filter(c => !same(c.before, c.after))
    .sort((a, b) => (a.relative < b.relative ? -1 : a.relative > b.relative ? 1 : 0));
  return { root, state, changes: pending };
}

function removeForkLinks(root: string, deleted: string[]) {
  const res = path.join(root, "src", "res");
  for (const file of deleted) {
    if (!inside(file, res)) continue;
    const link = path.join(root, "worktree", "TMessagesProj", "src", "main", "res", path.relative(res, file));
    try {
      if (fs.lstatSync(link).isSymbolicLink() && !fs.existsSync(link)) fs.unlinkSync(link);
    } catch {}
  }
}

export function uninstall(repo: string, bundle = BUNDLE, check = false, upstream?: string, runtimeUpstream?: string) {
  bundle = path.resolve(bundle);
  const manifest = loadManifest(bundle);
  const cache = path.join(bundle, "var", "cache");
  const { root, state, changes } = withTemp("entiny-plugin-revert-", temporary => {
    const [engine, runtime] = sources(manifest, upstream, runtimeUpstream, fs.existsSync(cache) ? cache : temporary);
    return buildUnplan(repo, bundle, manifest, engine, runtime);
  });
  for (const change of changes) console.log((change.after === null ? "remove " : "revert ") + change.relative);
  if (check || !changes.length) {
    console.log(`uninstall preflight passed: ${changes.length} file(s)` + (check ? "; target unchanged" : ""));
    if (!check) fs.rmSync(path.join(state, "installed.json"), { force: true });
    return changes;
  }
  const backup = path.join(state, "backups", "uninstall-" + randomUUID().replaceAll("-", ""));
  fs.mkdirSync(backup, { recursive: true });
  const records = changes.map((change, index) => {
    fs.writeFileSync(path.join(backup, `${index}.bin`), change.before!);
    return { path: change.relative, backup: `${index}.bin` };
  });
  fs.writeFileSync(path.join(backup, "transaction.json"), JSON.stringify({ repo: root, files: records }, null, 2));
  for (const change of changes) {
    if (!same(readFile(change.path), change.before)) throw new InstallError(`file changed during uninstall: ${change.relative}`);
  }
  const deleted: string[] = [];
  for (const change of changes) {
    if (change.after === null) {
      fs.unlinkSync(change.path);
      deleted.push(change.path);
      for (let parent = path.dirname(change.path); parent !== root; parent = path.dirname(parent)) {
        try { fs.rmdirSync(parent); } catch { break; }
      }
    } else atomicWrite(change.path, change.after);
  }
  removeForkLinks(root, deleted);
  fs.rmSync(path.join(state, "installed.json"), { force: true });
  console.log(`uninstalled: ${changes.length} file(s); backups: ${backup}`);
  return changes;
}

function linkForkResources(repo: string) {
  const bun = Bun.which("bun");
  if (!bun || !fs.existsSync(path.join(repo, "assets", "scripts", "lib.ts")) || !fs.existsSync(path.join(repo, "worktree"))) return;
  const code = "import { linkForkSource } from './assets/scripts/lib.ts'; await linkForkSource(Bun.env.ENTINY_LINK_TARGET, false)";
  const result = spawnSync(bun, ["-e", code], { cwd: repo, stdio: "inherit",
    env: { ...process.env, ENTINY_LINK_TARGET: path.join(repo, "worktree") } });
  if (result.status) throw new InstallError("linking fork resources failed");
}

function sources(manifest: Manifest, upstream: string | undefined, runtimeUpstream: string | undefined, cache: string) {
  const engine = acquire(manifest.upstream, upstream, cache);
  const runtime = manifest.runtime_vendor?.length ? acquire(manifest.runtime_upstream!, runtimeUpstream, cache) : undefined;
  return [engine, runtime] as const;
}

function withTemp<T>(prefix: string, body: (dir: string) => T): T {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  try {
    return body(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

export function run(repo: string, upstream?: string, runtimeUpstream?: string, check = false, bundle = BUNDLE) {
  bundle = path.resolve(bundle);
  const manifest = loadManifest(bundle);
  return withTemp("entiny-plugin-check-", temporary => {
    const [engine, runtime] = sources(manifest, upstream, runtimeUpstream, check ? temporary : path.join(bundle, "var", "cache"));
    const plan = buildPlan(repo, bundle, manifest, engine, runtime);
    for (const change of plan.changes) console.log((change.before === null ? "add " : "edit ") + change.relative);
    if (check) console.log(`preflight passed: ${plan.changes.length} file(s); target unchanged`);
    else {
      const backup = applyPlan(plan);
      console.log(`applied: ${plan.changes.length} file(s)` + (backup ? `; backups: ${backup}` : " (no changes)"));
      linkForkResources(path.resolve(repo));
    }
    return plan;
  });
}

export function status(repo: string, upstream?: string, runtimeUpstream?: string, bundle = BUNDLE) {
  bundle = path.resolve(bundle);
  const manifest = loadManifest(bundle);
  const cache = path.join(bundle, "var", "cache");
  const plan = withTemp("entiny-plugin-status-", temporary => {
    const [engine, runtime] = sources(manifest, upstream, runtimeUpstream, fs.existsSync(cache) ? cache : temporary);
    return buildPlan(repo, bundle, manifest, engine, runtime);
  });
  const total = Object.keys(plan.installed).length;
  const pending = plan.changes.length;
  const state = !pending ? "applied" : pending === total ? "not applied" : "partly applied";
  const host = plan.changes.map(c => c.relative).filter(r => !r.startsWith("extera-plugins/"));
  console.log(`${state}: ${total - pending}/${total} file(s) in place, ${host.length} host file(s) pending`);
  for (const name of host) console.log("  pending " + name);
  return state;
}

export function hookMap(bundle = BUNDLE) {
  const rows = new Map<string, [string, number, number]>();
  const dir = path.join(bundle, "manifest");
  for (const part of fs.readdirSync(dir).filter(n => /^edits-.*\.json$/.test(n)).sort()) {
    const area = part.slice("edits-".length, -".json".length);
    for (const edit of JSON.parse(fs.readFileSync(path.join(dir, part), "utf-8")).edits as Edit[]) {
      const row = rows.get(edit.path) ?? [area, 0, 0];
      row[1] += 1;
      row[2] += edit.after.split("\n").length - edit.before.split("\n").length;
      rows.set(edit.path, row);
    }
  }
  const lines = ["| area | file | hunks | net lines |", "| --- | --- | --- | --- |"];
  const order = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
  const sorted = [...rows].sort((a, b) => order(a[1][0], b[1][0]) || order(a[0], b[0]));
  for (const [file, [area, hunks, net]] of sorted) lines.push(`| ${area} | \`${file}\` | ${hunks} | ${net >= 0 ? "+" : ""}${net} |`);
  return lines.join("\n");
}
