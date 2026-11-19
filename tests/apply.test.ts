import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { spawnSync } from "node:child_process";
import * as overlay from "../lib/overlay.ts";

const quiet = <T>(body: () => T): T => {
  const log = console.log;
  console.log = () => {};
  try {
    return body();
  } finally {
    console.log = log;
  }
};
const b = (s: string) => Buffer.from(s, "utf-8");
const write = (file: string, data: Buffer | string) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};
const gitIn = (dir: string, ...args: string[]) => {
  const r = spawnSync("git", ["-C", dir, ...args]);
  if (r.status) throw new Error(r.stderr.toString());
  return r.stdout.toString();
};
const commitAll = (dir: string, message: string) => {
  gitIn(dir, "init", "--quiet");
  gitIn(dir, "add", ".");
  gitIn(dir, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", message);
};

describe("overlay", () => {
  let base: string, repo: string, bundle: string, upstream: string, manifest: overlay.Manifest;

  beforeEach(() => {
    base = fs.mkdtempSync(path.join(os.tmpdir(), "entiny-installer-test-"));
    [repo, bundle, upstream] = ["checkout", "bundle", "upstream"].map(n => path.join(base, n));
    for (const d of [repo, bundle, upstream]) fs.mkdirSync(d);
    write(path.join(upstream, "engine/Engine.java"), "class Engine {\n    old();\n}\n");
    write(path.join(upstream, "engine/ui/Foreign.java"), "foreign screen\n");
    write(path.join(upstream, "runtime/native.c"), "runtime\n");
    commitAll(upstream, "fixture");
    const commit = gitIn(upstream, "rev-parse", "HEAD").trim();
    write(path.join(repo, "src/Config.kt"), Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), b("val ready = false\r\n// untouched α\r\n")]));
    write(path.join(repo, "src/res/values/strings.xml"), '<?xml version="1.0" encoding="utf-8"?>\r\n<resources>\r\n'
      + '    <string name="existing">same &amp; value</string>\r\n    <string name="unrelated">keep</string>\r\n</resources>\r\n');
    write(path.join(bundle, "payload/Bridge.kt"), "class Bridge\n");
    write(path.join(bundle, "payload/strings.xml"),
      '<resources>\n<string name="existing">same &amp; value</string>\n<string name="added">new</string>\n</resources>\n');
    const source = { url: upstream, commit };
    manifest = {
      upstream: source, runtime_upstream: source,
      vendor: [{ source: "engine", target: "extera-plugins/engine", exclude: ["ui"] }],
      runtime_vendor: [{ source: "runtime", target: "extera-plugins/runtime" }],
      vendor_edits: [{ path: "extera-plugins/engine/Engine.java", before: "    old();", after: "    adapted();" }],
      files: [{ source: "payload/Bridge.kt", target: "src/Bridge.kt" }],
      edits: [{ path: "src/Config.kt", before: "val ready = false", alternatives: ["val ready = draft"],
        after: "val ready = false\nval python = true" }],
      resources: [{ source: "payload/strings.xml", target: "src/res/values/strings.xml" }],
    };
  });

  afterEach(() => fs.rmSync(base, { recursive: true, force: true }));

  const snapshot = () => {
    const out: Record<string, string> = {};
    const walk = (dir: string) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) walk(full);
        else if (e.isFile()) out[path.relative(repo, full).replaceAll("\\", "/")] = fs.readFileSync(full).toString("base64");
      }
    };
    walk(repo);
    return out;
  };
  const state = () => path.join(bundle, "var", "state");
  const found = (name: string) => (fs.existsSync(state()) ? fs.readdirSync(state(), { recursive: true }) as string[] : [])
    .filter(p => path.basename(p) === name);
  const install = (check = false) => {
    write(path.join(bundle, "manifest.json"), JSON.stringify(manifest));
    return quiet(() => overlay.run(repo, upstream, upstream, check, bundle));
  };
  const uninstall = (check = false) => quiet(() => overlay.uninstall(repo, bundle, check, upstream, upstream));
  const read = (rel: string) => fs.readFileSync(path.join(repo, rel));

  test("offline preflight, apply and repeat preserve format", () => {
    const before = snapshot();
    expect(install(true).changes.length).toBe(5);
    expect(snapshot()).toEqual(before);
    expect(fs.existsSync(state())).toBe(false);
    install();
    expect(read("src/Config.kt")).toEqual(Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]),
      b("val ready = false\r\nval python = true\r\n// untouched α\r\n")]));
    expect(fs.existsSync(path.join(repo, "extera-plugins/engine/ui"))).toBe(false);
    expect(read("extera-plugins/engine/Engine.java").toString()).toContain("adapted();");
    const resources = read("src/res/values/strings.xml").toString();
    expect(resources).toContain('<string name="unrelated">keep</string>\r\n');
    expect(resources).toContain('<string name="added">new</string>\r\n');
    expect(resources.replaceAll("\r\n", "")).not.toContain("\n");
    const applied = snapshot();
    const transactions = found("transaction.json");
    expect(transactions.length).toBe(1);
    expect(install().changes).toEqual([]);
    expect(snapshot()).toEqual(applied);
    expect(found("transaction.json")).toEqual(transactions);
    expect(gitIn(upstream, "status", "--porcelain")).toBe("");
  });

  test("uninstall restores the checkout and keeps foreign edits", () => {
    commitAll(repo, "original");
    const original = snapshot();
    install();
    const config = path.join(repo, "src/Config.kt");
    fs.writeFileSync(config, Buffer.concat([fs.readFileSync(config), b("// mine\r\n")]));
    const res = path.join(repo, "src/res/values/strings.xml");
    fs.writeFileSync(res, fs.readFileSync(res, "utf-8").replace("</resources>", '    <string name="mine">x</string>\r\n</resources>'));
    const before = snapshot();
    uninstall(true);
    expect(snapshot()).toEqual(before);
    uninstall();
    expect(fs.existsSync(path.join(repo, "src/Bridge.kt"))).toBe(false);
    expect(read("src/Config.kt")).toEqual(Buffer.concat([Buffer.from(original["src/Config.kt"], "base64"), b("// mine\r\n")]));
    const strings = read("src/res/values/strings.xml").toString();
    expect(strings).toContain('<string name="mine">x</string>');
    expect(strings).not.toContain('name="added"');
    expect(strings).toContain('<string name="existing">same &amp; value</string>');
    expect(found("installed.json")).toEqual([]);
    expect(uninstall().length).toBe(0);
    install();
    expect(read("src/Config.kt").toString()).toContain("val python = true");
  });

  test("revert restores tracked payload targets and removes vendored host files", () => {
    write(path.join(repo, "src/Tracked.kt"), "original\n");
    write(path.join(bundle, "payload/Tracked.kt"), "overlay version\n");
    manifest.files!.push({ source: "payload/Tracked.kt", target: "src/Tracked.kt", previous_sha256: [overlay.digest(b("original\n"))] });
    manifest.vendor!.push({ source: "runtime", target: "src/res/vendored" });
    commitAll(repo, "original");
    const original = snapshot();
    install();
    expect(read("src/Tracked.kt").toString()).toBe("overlay version\n");
    expect(fs.existsSync(path.join(repo, "src/res/vendored/native.c"))).toBe(true);
    expect(uninstall(true).length).toBeGreaterThan(0);
    uninstall();
    const restored = Object.fromEntries(Object.entries(snapshot()).filter(([k]) => !k.startsWith("extera-plugins/") && !k.startsWith(".git/")));
    const expected = Object.fromEntries(Object.entries(original).filter(([k]) => !k.startsWith(".git/")));
    expect(restored).toEqual(expected);
    expect(uninstall(true).length).toBe(0);
  });

  test("uninstall refuses a payload file changed by hand", () => {
    install();
    fs.writeFileSync(path.join(repo, "src/Bridge.kt"), "class Bridge { changed }\n");
    const before = snapshot();
    expect(() => uninstall()).toThrow(overlay.InstallError);
    expect(snapshot()).toEqual(before);
  });

  test("unknown payload conflict aborts before any write", () => {
    write(path.join(repo, "src/Bridge.kt"), "user work\n");
    const before = snapshot();
    expect(() => install()).toThrow(/unknown existing content/);
    expect(snapshot()).toEqual(before);
    expect(fs.existsSync(state())).toBe(false);
  });

  test("known draft and alternative are explicitly accepted", () => {
    const draft = b("known draft engine\r\n");
    write(path.join(repo, "extera-plugins/engine/Engine.java"), draft);
    manifest.known_drafts = { "extera-plugins/engine/Engine.java": [overlay.digest(draft)] };
    write(path.join(repo, "src/Config.kt"), "val ready = draft\r\n");
    install();
    expect(read("extera-plugins/engine/Engine.java").toString()).toContain("adapted();\r\n");
    expect(read("src/Config.kt").toString()).toBe("val ready = false\r\nval python = true\r\n");
  });

  test("ambiguous anchor aborts before any write", () => {
    write(path.join(repo, "src/Config.kt"), "val ready = false\nval ready = false\n");
    const before = snapshot();
    expect(() => install()).toThrow(/anchor conflict/);
    expect(snapshot()).toEqual(before);
  });

  test("resource conflict preserves unrelated and pending changes", () => {
    const res = path.join(repo, "src/res/values/strings.xml");
    fs.writeFileSync(res, fs.readFileSync(res, "utf-8").replace("same &amp; value", "my translation"));
    const before = snapshot();
    expect(() => install()).toThrow(/resource conflict/);
    expect(snapshot()).toEqual(before);
  });

  test("path traversal and git metadata are rejected", () => {
    for (const target of ["../outside", "/outside", "C:\\outside", "\\\\server\\share\\x", ".git/config", "src/../outside"]) {
      manifest.files![0].target = target;
      const before = snapshot();
      expect(() => install()).toThrow(overlay.InstallError);
      expect(snapshot()).toEqual(before);
    }
  });

  test("symlink escape is rejected", () => {
    const outside = path.join(base, "outside");
    fs.mkdirSync(outside);
    try {
      fs.symlinkSync(outside, path.join(repo, "escape"), "junction");
    } catch {
      return;
    }
    manifest.files![0].target = "escape/Bridge.kt";
    expect(() => install()).toThrow(/escapes/);
    expect(fs.readdirSync(outside)).toEqual([]);
  });

  test("symlinked overlay source is rejected", () => {
    const real = path.join(base, "real");
    write(path.join(real, "Bridge.kt"), "class Bridge\n");
    fs.rmSync(path.join(bundle, "payload"), { recursive: true });
    try {
      fs.symlinkSync(real, path.join(bundle, "payload"), "junction");
    } catch {
      return;
    }
    manifest.resources = [];
    expect(() => install()).toThrow(/not links/);
  });

  test("write failure rolls back and keeps backups", () => {
    const before = snapshot();
    const original = overlay.fsio.atomicWrite;
    let failed = false;
    overlay.fsio.atomicWrite = (file, data) => {
      if (path.resolve(file) === fs.realpathSync.native(path.join(repo, "src/Config.kt")) && !failed) {
        failed = true;
        throw new Error("injected write failure");
      }
      original(file, data);
    };
    try {
      expect(() => install()).toThrow(/target changes rolled back/);
    } finally {
      overlay.fsio.atomicWrite = original;
    }
    expect(failed).toBe(true);
    expect(snapshot()).toEqual(before);
    expect(fs.existsSync(path.join(repo, "extera-plugins"))).toBe(false);
    const transactions = found("transaction.json");
    expect(transactions.length).toBe(1);
    const file = path.join(state(), transactions[0]);
    const record = JSON.parse(fs.readFileSync(file, "utf-8")).files.find((r: any) => r.path === "src/Config.kt");
    expect(fs.readFileSync(path.join(path.dirname(file), record.backup)).toString("base64")).toBe(before["src/Config.kt"]);
    expect(found("installed.json")).toEqual([]);
  });

  test("split manifest merges lists and rejects repeated keys", () => {
    const parts = path.join(bundle, "manifest");
    write(path.join(parts, "a.json"), JSON.stringify({ upstream: { url: "u" }, edits: [{ path: "a" }] }));
    write(path.join(parts, "b.json"), JSON.stringify({ edits: [{ path: "b" }], known_drafts: { x: [] } }));
    const merged = overlay.loadManifest(bundle);
    expect(merged.edits!.map(e => e.path)).toEqual(["a", "b"]);
    expect(merged.known_drafts).toEqual({ x: [] });
    write(path.join(parts, "c.json"), JSON.stringify({ upstream: {} }));
    expect(() => overlay.loadManifest(bundle)).toThrow(overlay.InstallError);
  });
});

test("the real bundle has no links and a current hook registry", () => {
  const walk = (dir: string): string[] => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const full = path.join(dir, e.name);
    if (e.isSymbolicLink()) return [full];
    return e.isDirectory() ? walk(full) : [];
  });
  expect(walk(path.join(overlay.BUNDLE, "overlay"))).toEqual([]);
  const doc = fs.readFileSync(path.join(overlay.BUNDLE, "docs", "hooks.md"), "utf-8").replaceAll("\r\n", "\n");
  expect(doc).toContain(overlay.hookMap());
});
