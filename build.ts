import * as fs from "node:fs";
import * as path from "node:path";
import { spawnSync } from "node:child_process";
import { parseArgs } from "node:util";
import * as overlay from "./lib/overlay.ts";

export const PYTHON = "3.11";
const PROBE = "import sys; print(sys.executable); print('%d.%d' % sys.version_info[:2])";
const VERSION_LINE = /^build version:[ \t]*(["']?)([0-9]{10})\1[ \t]*(?:#.*)?$/;
const BOM = Buffer.from([0xef, 0xbb, 0xbf]);

export type Args = {
  repo: string; config: string; abi: "arm64-v8a" | "x86_64"; buildType: "release" | "beta" | "debug";
  upstream?: string; runtimeUpstream?: string; allowAhead?: boolean; keepApplied?: boolean; dryRun?: boolean;
};

export const deps = {
  run(command: string[], options: { cwd: string; env: Record<string, string | undefined> }) {
    const result = spawnSync(command[0], command.slice(1), { ...options, stdio: "inherit" });
    if (result.error) throw result.error;
    if (result.status) throw new Error(`command failed (${result.status}): ${command.join(" ")}`);
  },
  which: (name: string) => Bun.which(name),
  today: () => new Date(),
  buildPython,
  copy: (source: string, target: string) => fs.copyFileSync(source, target, fs.constants.COPYFILE_EXCL),
  apply: overlay.run,
  revert: overlay.uninstall,
};

export function buildPython() {
  for (const command of [["py", `-${PYTHON}`], [`python${PYTHON}`], ["python"]]) {
    const result = spawnSync(command[0], [...command.slice(1), "-c", PROBE], { encoding: "utf-8", timeout: 30000 });
    const lines = (result.stdout ?? "").split(/\r?\n/).filter(Boolean);
    if (!result.error && result.status === 0 && lines.length === 2 && lines[1] === PYTHON) return lines[0];
  }
  return null;
}

function text(data: Buffer) {
  const s = data.toString("utf-8");
  return s.startsWith("﻿") ? s.slice(1) : s;
}

export function readConfig(file: string) {
  const data = fs.readFileSync(file);
  const versions: string[] = [];
  for (const line of text(data).split(/\r?\n|\r/)) {
    if (!line.trim() || line.trimStart().startsWith("#")) continue;
    const match = VERSION_LINE.exec(line);
    if (!match) throw new Error(`${file}: expected build version: "YYYYMMDDNN"`);
    versions.push(match[2]);
  }
  if (versions.length !== 1) throw new Error(`${file}: expected exactly one build version`);
  return { data, version: versions[0] };
}

const utcDay = (d: Date) => d.toISOString().slice(0, 10).replaceAll("-", "");

export function nextVersion(previous: string, today = deps.today(), allowAhead = false) {
  if (!/^[0-9]{10}$/.test(previous)) throw new Error("build version must use YYYYMMDDNN");
  const [y, m, d] = [previous.slice(0, 4), previous.slice(4, 6), previous.slice(6, 8)].map(Number);
  const parsed = new Date(Date.UTC(y, m - 1, d));
  if (parsed.getUTCFullYear() !== y || parsed.getUTCMonth() !== m - 1 || parsed.getUTCDate() !== d) {
    throw new Error("build version must use YYYYMMDDNN");
  }
  const day = utcDay(today);
  const check = (version: string) => {
    if (Number(version) > 2_147_483_647) throw new Error("build version exceeds Android's integer versionCode");
    return version;
  };
  if (previous.slice(0, 8) > day) {
    if (!allowAhead) throw new Error("config date is ahead of the current UTC date");
    return check(String(Number(previous) + 1));
  }
  const counter = previous.slice(0, 8) === day ? Number(previous.slice(8)) + 1 : 0;
  if (counter > 99) throw new Error("all 100 build numbers for this UTC day are used");
  return check(day + String(counter).padStart(2, "0"));
}

export function withLock<T>(config: string, body: () => T): T {
  const lock = config + ".lock";
  let fd: number;
  try {
    fd = fs.openSync(lock, "wx");
  } catch {
    throw new Error(`another build owns ${lock}; remove it only if that build has stopped`);
  }
  try {
    fs.writeSync(fd, String(process.pid));
    fs.closeSync(fd);
    return body();
  } finally {
    fs.rmSync(lock, { force: true });
  }
}

export function saveVersion(file: string, before: Buffer, version: string) {
  if (!fs.readFileSync(file).equals(before)) throw new Error(`${file} changed during the build; refusing to overwrite it`);
  const body = Buffer.from(text(before).replace(/^(build version:[ \t]*["']?)[0-9]{10}/m, `$1${version}`), "utf-8");
  overlay.atomicWrite(file, before.subarray(0, 3).equals(BOM) ? Buffer.concat([BOM, body]) : body);
}

export function builtApk(repo: string, variant: string, version: string) {
  const directory = path.join(repo, "worktree/TMessagesProj_App/build/outputs/apk", variant);
  const elements = JSON.parse(fs.readFileSync(path.join(directory, "output-metadata.json"), "utf-8")).elements;
  if (elements.length !== 1 || String(elements[0].versionCode) !== version) {
    throw new Error("APK metadata does not contain the requested build version");
  }
  const apk = path.resolve(directory, elements[0].outputFile);
  const rel = path.relative(path.resolve(directory), apk);
  if (rel.startsWith("..") || path.isAbsolute(rel) || !fs.existsSync(apk) || !fs.statSync(apk).isFile()) {
    throw new Error("APK metadata points to a missing or invalid output file");
  }
  return apk;
}

export function artifactPath(repo: string, abi: string, version: string, bundle = overlay.BUNDLE) {
  const properties = text(fs.readFileSync(path.join(repo, "worktree/gradle.properties")));
  const names = [...properties.matchAll(/^APP_VERSION_NAME[ \t]*=[ \t]*([a-zA-Z0-9][a-zA-Z0-9._-]*)[ \t]*\r?$/gm)].map(m => m[1]);
  if (names.length !== 1) throw new Error("expected one filename-safe APP_VERSION_NAME in gradle.properties");
  const architecture = abi === "arm64-v8a" ? "arm64" : abi;
  return path.join(bundle, "var", "out", `entinygram-private-${architecture}-${names[0]}-${version}.apk`);
}

export function run(args: Args, bundle = overlay.BUNDLE) {
  const repo = path.resolve(args.repo);
  const config = path.resolve(args.config);
  const variant = args.buildType === "debug" ? "debug" : "release";
  const wrapper = path.join(repo, "worktree/gradle/wrapper/gradle-wrapper.jar");
  if (!fs.existsSync(wrapper) || !fs.existsSync(path.join(repo, "package.json"))) {
    throw new Error("prepare the entinyGram worktree before running this script");
  }
  const javaHome = process.env.JAVA_HOME;
  const java = javaHome ? path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java") : deps.which("java");
  const bun = deps.which("bun");
  if (!args.dryRun && (!java || !fs.existsSync(java) || !bun)) throw new Error("Java and Bun must be available; check JAVA_HOME and PATH");
  const commands = [
    [bun ?? "bun", "run", "generate-grants"],
    [java ?? "java", "-Dorg.gradle.appname=gradlew", "-classpath", wrapper, "org.gradle.wrapper.GradleWrapperMain",
      "--no-daemon", "-Pinu.pluginless=false", `:TMessagesProj_App:assemble${variant[0].toUpperCase()}${variant.slice(1)}`],
  ];
  const python = process.env.CHAQUOPY_BUILD_PYTHON || deps.buildPython();
  if (!args.dryRun && !python) throw new Error(`Python ${PYTHON} is required for Chaquopy; install it or set CHAQUOPY_BUILD_PYTHON`);
  if (args.dryRun) {
    console.log(`CHAQUOPY_BUILD_PYTHON=${python ?? `missing (needs Python ${PYTHON})`}`);
    const { version: previous } = readConfig(config);
    const version = nextVersion(previous, deps.today(), args.allowAhead);
    console.log(`${previous} -> ${version} (UTC); config unchanged`);
    console.log(`INU_BUILD=${version} INU_BUILD_TYPE=${args.buildType} ABI_FILTERS=${args.abi}`);
    console.log(`apply ${path.join(bundle, "apply.ts")} to ${repo}`);
    for (const command of commands) console.log(command.map(c => (/\s/.test(c) ? `"${c}"` : c)).join(" "));
    console.log(`APK: ${artifactPath(repo, args.abi, version, bundle)}`);
    return version;
  }
  return withLock(config, () => {
    const { data: before, version: previous } = readConfig(config);
    const version = nextVersion(previous, deps.today(), args.allowAhead);
    const output = artifactPath(repo, args.abi, version, bundle);
    if (fs.existsSync(output)) throw new Error(`APK already exists: ${output}; check build version in ${config}`);
    const env = { ...process.env, INU_BUILD: version, INU_BUILD_TYPE: args.buildType, ABI_FILTERS: args.abi,
      CHAQUOPY_BUILD_PYTHON: python! };
    console.log(`building ${version} (${args.buildType}, ${args.abi}, UTC)`);
    deps.apply(repo, args.upstream, args.runtimeUpstream);
    let apk: string;
    try {
      deps.run(commands[0], { cwd: repo, env });
      deps.run(commands[1], { cwd: path.join(repo, "worktree"), env });
      apk = builtApk(repo, variant, version);
    } finally {
      if (!args.keepApplied) {
        try {
          deps.revert(repo);
        } catch (error) {
          console.error(`warning: the overlay was not taken out again: ${(error as Error).message}`);
        }
      }
    }
    fs.mkdirSync(path.dirname(output), { recursive: true });
    for (const old of fs.readdirSync(path.dirname(output))) {
      if (/^entinygram-private-.*\.apk$/.test(old)) fs.rmSync(path.join(path.dirname(output), old), { force: true });
    }
    try {
      deps.copy(apk!, output);
    } catch (error) {
      fs.rmSync(output, { force: true });
      throw error;
    }
    saveVersion(config, before, version);
    console.log(`built ${version}: ${output}`);
    return version;
  });
}

const HELP = `usage: bun extera-plugins/build.ts [--dry-run] [--build-type release|beta|debug] [--abi arm64-v8a|x86_64]
  --repo <dir> --config <yaml> --upstream <git> --runtime-upstream <git> --allow-ahead --keep-applied`;

export function main(argv = Bun.argv.slice(2)) {
  const { values } = parseArgs({
    args: argv,
    options: {
      repo: { type: "string" }, config: { type: "string" }, abi: { type: "string" }, "build-type": { type: "string" },
      upstream: { type: "string" }, "runtime-upstream": { type: "string" }, "allow-ahead": { type: "boolean" },
      "keep-applied": { type: "boolean" }, "dry-run": { type: "boolean" }, help: { type: "boolean", short: "h" },
    },
  });
  if (values.help) {
    console.log(HELP);
    return 0;
  }
  const abi = values.abi ?? "arm64-v8a";
  const buildType = values["build-type"] ?? "release";
  try {
    if (!["arm64-v8a", "x86_64"].includes(abi)) throw new Error(`--abi must be arm64-v8a or x86_64`);
    if (!["release", "beta", "debug"].includes(buildType)) throw new Error(`--build-type must be release, beta or debug`);
    run({
      repo: values.repo ?? path.dirname(overlay.BUNDLE),
      config: values.config ?? path.join(overlay.BUNDLE, "var", "config.yaml"),
      abi: abi as Args["abi"], buildType: buildType as Args["buildType"],
      upstream: values.upstream, runtimeUpstream: values["runtime-upstream"],
      allowAhead: values["allow-ahead"], keepApplied: values["keep-applied"], dryRun: values["dry-run"],
    });
  } catch (error) {
    console.error(`error: ${(error as Error).message}`);
    return 1;
  }
  return 0;
}

if (import.meta.main) process.exit(main());
