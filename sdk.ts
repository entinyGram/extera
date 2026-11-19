import { createHash, createPrivateKey, sign } from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import { parseArgs } from "node:util";
import { deflateRawSync } from "node:zlib";
import { BUNDLE } from "./lib/overlay.ts";

const URL = "https://github.com/entinyGram/plugins/releases/download/python-sdk/sdk-{version}.zip";
const PAYLOAD = "entiny-pysdk|{version}|{sha256}";
const ALLOWED = new Set([".py", ".txt", ".json"]);
const DOS_TIME = 0;
const DOS_DATE = ((2026 - 1980) << 9) | (1 << 5) | 1;

function files(source: string): string[] {
  const result: string[] = [];
  const walk = (dir: string) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== "__pycache__") walk(full);
      } else if (entry.isFile() && ALLOWED.has(path.extname(entry.name))) {
        result.push(path.relative(source, full).replaceAll("\\", "/"));
      }
    }
  };
  walk(source);
  return result.sort();
}

export function pack(source: string, target: string) {
  const local: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  const names = files(source);
  for (const name of names) {
    const data = fs.readFileSync(path.join(source, name));
    const packed = deflateRawSync(data);
    const crc = Bun.hash.crc32(data);
    const nameBytes = Buffer.from(name, "utf-8");
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50, 0);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(0x0800, 6);
    header.writeUInt16LE(8, 8);
    header.writeUInt16LE(DOS_TIME, 10);
    header.writeUInt16LE(DOS_DATE, 12);
    header.writeUInt32LE(crc, 14);
    header.writeUInt32LE(packed.length, 18);
    header.writeUInt32LE(data.length, 22);
    header.writeUInt16LE(nameBytes.length, 26);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(20, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(0x0800, 8);
    entry.writeUInt16LE(8, 10);
    entry.writeUInt16LE(DOS_TIME, 12);
    entry.writeUInt16LE(DOS_DATE, 14);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(packed.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt32LE(offset, 42);
    local.push(header, nameBytes, packed);
    central.push(entry, nameBytes);
    offset += header.length + nameBytes.length + packed.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(names.length, 8);
  end.writeUInt16LE(names.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  fs.writeFileSync(target, Buffer.concat([...local, directory, end]));
  return names.length;
}

export function signature(key: string, version: string, sha256: string) {
  const message = Buffer.from(PAYLOAD.replace("{version}", version).replace("{sha256}", sha256));
  return sign("sha256", message, createPrivateKey(fs.readFileSync(key))).toString("base64");
}

export function main(argv = Bun.argv.slice(2)) {
  const { values, positionals } = parseArgs({ args: argv, allowPositionals: true, options: { key: { type: "string" } } });
  const version = positionals[0];
  if (!version || !/^[0-9]{1,4}(\.[0-9]{1,4}){1,3}$/.test(version)) {
    console.error("usage: bun extera-plugins/sdk.ts <version like 1.0.1> --key <sdk-signing.pem>");
    return 1;
  }
  if (!values.key || !fs.existsSync(values.key)) {
    console.error(`no key at ${values.key}`);
    return 1;
  }
  const source = path.join(BUNDLE, "engine", "python");
  if (!fs.existsSync(source)) {
    console.error("engine/python is missing; run apply.ts, then apply.ts --revert");
    return 1;
  }
  const out = path.join(BUNDLE, "var", "out", "sdk");
  fs.mkdirSync(out, { recursive: true });
  const zip = path.join(out, `sdk-${version}.zip`);
  const count = pack(source, zip);
  const sha256 = createHash("sha256").update(fs.readFileSync(zip)).digest("hex");
  const manifest = { stable: { version, url: URL.replace("{version}", version), sha256, signature: signature(values.key, version, sha256) } };
  fs.writeFileSync(path.join(out, "sdk.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log(`${count} files, ${fs.statSync(zip).size} bytes, sha256 ${sha256}`);
  console.log(`wrote ${path.join(out, "sdk.json")}`);
  return 0;
}

if (import.meta.main) process.exit(main());
