import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import * as build from "../build.ts";
import { InstallError } from "../lib/overlay.ts";

const write = (file: string, data: Buffer | string) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};
const day = (s: string) => new Date(`${s}T12:00:00Z`);

describe("build runner", () => {
  let base: string, repo: string, bundle: string, config: string, wrapper: string, java: string, bun: string;
  let before: Buffer, args: build.Args, calls: { command: string[]; cwd: string; env: Record<string, string | undefined> }[];
  let applied: number, reverted: number;
  let execute: (command: string[], options: { cwd: string; env: Record<string, string | undefined> }) => void;
  const saved = { ...build.deps };
  const env = { ...process.env };
  let lines: string[];
  const log = console.log, err = console.error;

  beforeEach(() => {
    base = fs.mkdtempSync(path.join(os.tmpdir(), "entiny-build-test-"));
    repo = path.join(base, "checkout");
    bundle = path.join(base, "bundle");
    wrapper = path.join(repo, "worktree/gradle/wrapper/gradle-wrapper.jar");
    write(wrapper, "fixture wrapper");
    write(path.join(repo, "package.json"), "{}");
    write(path.join(repo, "worktree/gradle.properties"), "APP_VERSION_NAME=13.1.1\r\n");
    config = path.join(base, "config.yaml");
    before = Buffer.from('# private\r\nbuild version: "2026110600"\r\n');
    write(config, before);
    const javaHome = path.join(base, "jdk");
    java = path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
    bun = path.join(base, "bun.exe");
    write(java, "fixture java");
    write(bun, "fixture bun");
    process.env.JAVA_HOME = javaHome;
    process.env.INU_BUILD = "inherited";
    delete process.env.CHAQUOPY_BUILD_PYTHON;
    args = { repo, config, abi: "x86_64", buildType: "beta", upstream: path.join(base, "upstream"),
      runtimeUpstream: path.join(base, "runtime"), dryRun: false };
    calls = [];
    applied = reverted = 0;
    execute = () => {};
    lines = [];
    console.log = (...a: unknown[]) => lines.push(a.join(" "));
    console.error = () => {};
    Object.assign(build.deps, {
      which: (name: string) => (name === "bun" ? bun : java),
      today: () => day("2026-11-06"),
      buildPython: () => "fixture-python3.11",
      apply: () => { applied++; },
      revert: () => { reverted++; },
      run: (command: string[], options: { cwd: string; env: Record<string, string | undefined> }) => {
        calls.push({ command, ...options });
        execute(command, options);
      },
    });
  });

  afterEach(() => {
    Object.assign(build.deps, saved);
    process.env = { ...env };
    console.log = log;
    console.error = err;
    fs.rmSync(base, { recursive: true, force: true });
  });

  const artifact = (version = "2026110601", filename = "app.apk", count = 1) => {
    const variant = args.buildType === "debug" ? "debug" : "release";
    const directory = path.join(repo, "worktree/TMessagesProj_App/build/outputs/apk", variant);
    write(path.resolve(directory, filename), "fixture apk");
    write(path.join(directory, "output-metadata.json"),
      JSON.stringify({ elements: Array(count).fill({ versionCode: Number(version), outputFile: filename }) }));
  };
  const lock = () => config + ".lock";
  const output = (abi: string) => path.join(bundle, "var", "out",
    `entinygram-private-${abi === "arm64-v8a" ? "arm64" : abi}-13.1.1-2026110601.apk`);
  const repoFiles = () => (fs.readdirSync(repo, { recursive: true }) as string[]).sort();

  test("variants pass version, abi and python, then advance config after the copy", () => {
    for (const [kind, abi, task] of [["beta", "x86_64", "Release"], ["debug", "arm64-v8a", "Debug"], ["release", "arm64-v8a", "Release"]] as const) {
      fs.writeFileSync(config, before);
      args.buildType = kind;
      args.abi = abi;
      calls = [];
      applied = 0;
      if (kind === "debug") process.env.CHAQUOPY_BUILD_PYTHON = "explicit-python";
      else delete process.env.CHAQUOPY_BUILD_PYTHON;
      execute = (command, options) => {
        expect(fs.readFileSync(config)).toEqual(before);
        expect(fs.existsSync(lock())).toBe(true);
        expect([options.env.INU_BUILD, options.env.INU_BUILD_TYPE, options.env.ABI_FILTERS]).toEqual(["2026110601", kind, abi]);
        expect(options.env.CHAQUOPY_BUILD_PYTHON).toBe(kind === "debug" ? "explicit-python" : "fixture-python3.11");
        if (command[0] === java) artifact();
      };
      expect(build.run(args, bundle)).toBe("2026110601");
      expect(fs.readFileSync(output(abi)).toString()).toBe("fixture apk");
      expect(applied).toBe(1);
      expect(calls.length).toBe(2);
      expect(calls[0].command).toEqual([bun, "run", "generate-grants"]);
      expect(calls[0].cwd).toBe(path.resolve(repo));
      expect(calls[1].command).toEqual([java, "-Dorg.gradle.appname=gradlew", "-classpath", wrapper,
        "org.gradle.wrapper.GradleWrapperMain", "--no-daemon", "-Pinu.pluginless=false", `:TMessagesProj_App:assemble${task}`]);
      expect(calls[1].cwd).toBe(path.join(repo, "worktree"));
      expect(fs.readFileSync(config, "utf-8")).toBe(before.toString().replace("2026110600", "2026110601"));
      expect(fs.existsSync(lock())).toBe(false);
      expect(process.env.INU_BUILD).toBe("inherited");
      fs.rmSync(output(abi));
    }
  });

  test("failures never advance config", () => {
    const initial = repoFiles();
    for (const stage of ["install", "grants", "gradle"]) {
      build.deps.apply = () => { if (stage === "install") throw new InstallError("fixture failure"); };
      execute = command => {
        if ((stage === "grants") === (command[0] === bun)) throw new Error("command failed");
      };
      expect(() => build.run(args, bundle)).toThrow();
      expect(fs.readFileSync(config)).toEqual(before);
      expect(repoFiles()).toEqual(initial);
      expect(fs.existsSync(lock())).toBe(false);
    }
    build.deps.apply = () => {};
    for (const [version, filename, count] of [["2026110600", "app.apk", 1], ["2026110601", "app.apk", 2], ["2026110601", "../escaped.apk", 1]] as const) {
      execute = () => artifact(version, filename, count);
      expect(() => build.run(args, bundle)).toThrow();
      expect(fs.readFileSync(config)).toEqual(before);
      expect(fs.existsSync(lock())).toBe(false);
    }
  });

  test("overlay is taken out after the build unless kept", () => {
    execute = () => artifact("2026110601");
    build.run(args, bundle);
    expect(reverted).toBe(1);
    execute = () => { throw new Error("gradle"); };
    expect(() => build.run(args, bundle)).toThrow("gradle");
    expect(reverted).toBe(2);
    execute = () => artifact("2026110602");
    args.keepApplied = true;
    build.run(args, bundle);
    expect(reverted).toBe(2);
  });

  test("missing build python stops before any change", () => {
    build.deps.buildPython = () => null;
    const initial = repoFiles();
    expect(() => build.run(args, bundle)).toThrow(/Python 3\.11/);
    expect(applied).toBe(0);
    expect(calls.length).toBe(0);
    expect(repoFiles()).toEqual(initial);
    expect(fs.readFileSync(config)).toEqual(before);
  });

  test("dry run and an existing lock do not run tools or write the host", () => {
    const initial = repoFiles();
    args.dryRun = true;
    expect(build.run(args, bundle)).toBe("2026110601");
    expect(lines.join("\n")).toContain("-Pinu.pluginless=false");
    expect(lines.join("\n")).toContain("entinygram-private-x86_64-13.1.1-2026110601.apk");
    expect(fs.existsSync(path.join(bundle, "var", "out"))).toBe(false);
    expect(fs.readFileSync(config)).toEqual(before);
    expect(repoFiles()).toEqual(initial);
    expect(fs.existsSync(lock())).toBe(false);
    args.dryRun = false;
    fs.writeFileSync(lock(), "another process");
    expect(() => build.run(args, bundle)).toThrow(/another build owns/);
    expect(fs.readFileSync(lock(), "utf-8")).toBe("another process");
    expect(fs.readFileSync(config)).toEqual(before);
    expect(applied).toBe(0);
    expect(calls.length).toBe(0);
  });

  test("output collision and copy error preserve config", () => {
    args.abi = "arm64-v8a";
    write(output("arm64-v8a"), "previous artifact");
    expect(() => build.run(args, bundle)).toThrow(/APK already exists/);
    expect(fs.readFileSync(output("arm64-v8a"), "utf-8")).toBe("previous artifact");
    expect(fs.readFileSync(config)).toEqual(before);
    expect(applied).toBe(0);
    expect(calls.length).toBe(0);
    fs.rmSync(output("arm64-v8a"));
    execute = () => artifact();
    build.deps.copy = (_source, target) => {
      fs.writeFileSync(target, "partial artifact");
      throw new Error("fixture copy failure");
    };
    expect(() => build.run(args, bundle)).toThrow(/fixture copy failure/);
    expect(fs.existsSync(output("arm64-v8a"))).toBe(false);
    expect(fs.readFileSync(config)).toEqual(before);
    expect(fs.existsSync(lock())).toBe(false);
  });

  test("config changed during the build is preserved", () => {
    const changed = Buffer.concat([before, Buffer.from("# user edit\r\n")]);
    execute = command => {
      if (command[0] === java) {
        artifact();
        fs.writeFileSync(config, changed);
      }
    };
    expect(() => build.run(args, bundle)).toThrow(/changed during the build/);
    expect(fs.readFileSync(config)).toEqual(changed);
    expect(fs.existsSync(lock())).toBe(false);
  });
});

test("calendar counters and invalid config", () => {
  for (const [previous, today, expected] of [["2026110600", "2026-11-06", "2026110601"], ["2026110699", "2026-11-07", "2026110700"],
    ["2026123199", "2027-01-01", "2027010100"], ["2024022807", "2024-02-29", "2024022900"], ["2024022999", "2024-03-01", "2024030100"]]) {
    expect(build.nextVersion(previous, day(today))).toBe(expected);
  }
  for (const previous of ["202611060", "not-a-date", "2026023000", "2026110700", "2026110699"]) {
    expect(() => build.nextVersion(previous, day("2026-11-06"))).toThrow();
  }
  expect(() => build.nextVersion("2147123100", day("2148-01-01"))).toThrow(/integer versionCode/);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "entiny-build-config-test-"));
  try {
    const file = path.join(dir, "config.yaml");
    for (const value of ["", "build version: 202611060\n", "unknown: value\n", "build version: 2026110600\nbuild version: 2026110601\n"]) {
      fs.writeFileSync(file, value);
      expect(() => build.readConfig(file)).toThrow();
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
