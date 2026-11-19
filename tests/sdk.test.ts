import { expect, test } from "bun:test";
import { generateKeyPairSync, verify } from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { spawnSync } from "node:child_process";
import { inflateRawSync } from "node:zlib";
import { pack, signature } from "../sdk.ts";
import { buildPython } from "../build.ts";
import { BUNDLE } from "../lib/overlay.ts";

test("sdk zip is deterministic, filtered and readable", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "entiny-sdk-test-"));
  try {
    const source = path.join(dir, "python");
    for (const [name, body] of [["base_plugin.py", "x = 1\n"], ["ui/settings.py", "y = 2\n"], ["ui/__pycache__/a.pyc", "bin"], ["notes.md", "skip"]]) {
      fs.mkdirSync(path.dirname(path.join(source, name)), { recursive: true });
      fs.writeFileSync(path.join(source, name), body);
    }
    const first = path.join(dir, "a.zip"), second = path.join(dir, "b.zip");
    expect(pack(source, first)).toBe(2);
    pack(source, second);
    const zip = fs.readFileSync(first);
    expect(zip).toEqual(fs.readFileSync(second));
    const end = zip.length - 22;
    expect(zip.readUInt32LE(end)).toBe(0x06054b50);
    expect(zip.readUInt16LE(end + 10)).toBe(2);
    const names: string[] = [];
    for (let at = zip.readUInt32LE(end + 16), i = 0; i < 2; i++) {
      const size = zip.readUInt32LE(at + 20), length = zip.readUInt16LE(at + 28), offset = zip.readUInt32LE(at + 42);
      const name = zip.subarray(at + 46, at + 46 + length).toString();
      const start = offset + 30 + zip.readUInt16LE(offset + 26);
      const data = inflateRawSync(zip.subarray(start, start + size));
      expect(Bun.hash.crc32(data)).toBe(zip.readUInt32LE(at + 16));
      expect(data.toString()).toBe(fs.readFileSync(path.join(source, name), "utf-8"));
      names.push(name);
      at += 46 + length;
    }
    expect(names).toEqual(["base_plugin.py", "ui/settings.py"]);
    const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const key = path.join(dir, "key.pem");
    fs.writeFileSync(key, privateKey.export({ type: "pkcs8", format: "pem" }));
    const sig = Buffer.from(signature(key, "1.2.3", "abc"), "base64");
    expect(verify("sha256", Buffer.from("entiny-pysdk|1.2.3|abc"), publicKey, sig)).toBe(true);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("native runtime helper (python, runs inside gradle)", () => {
  const python = buildPython();
  if (!python) return;
  const result = spawnSync(python, ["-m", "unittest", "-q", "test_build_runtime"], { cwd: path.join(BUNDLE, "tests"), encoding: "utf-8" });
  expect(result.status, result.stderr).toBe(0);
});
