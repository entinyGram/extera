import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { parseArgs } from "node:util";
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

const files = (root: string) => {
  const out = new Map<string, string>();
  for (const rel of fs.readdirSync(root, { recursive: true }) as string[]) {
    const full = path.join(root, rel);
    if (!rel.startsWith(".git") && fs.statSync(full).isFile()) out.set(rel, fs.readFileSync(full).toString("base64"));
  }
  return out;
};

export function verify(repo: string, options: { upstream?: string; runtimeUpstream?: string; pinned?: boolean } = {}) {
  const bundle = overlay.BUNDLE;
  const manifest = overlay.loadManifest(bundle);
  const cache = path.join(bundle, "var", "cache");
  const engine = overlay.acquire(manifest.upstream, options.upstream, cache);
  const runtime = overlay.acquire(manifest.runtime_upstream!, options.runtimeUpstream, cache);
  const hostPaths = new Set([...(manifest.edits ?? []).map(e => e.path), ...(manifest.resources ?? []).map(r => r.target)]);
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "entiny-overlay-verify-"));

  const check = (mode: string) => {
    const target = path.join(temporary, mode);
    for (const rel of hostPaths) {
      const destination = path.join(target, rel);
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      if (mode === "current-checkout") {
        fs.copyFileSync(path.join(repo, rel), destination);
        continue;
      }
      const stock = rel.startsWith("worktree/");
      const revision = options.pinned ? manifest.host![stock ? "stock_commit" : "commit"] : "HEAD";
      const source = stock ? path.join(repo, "worktree") : repo;
      fs.writeFileSync(destination, overlay.git(source, ["show", `${revision}:${stock ? rel.slice("worktree/".length) : rel}`]));
    }
    if (mode === "current-checkout") {
      for (const rel of [...(manifest.vendor ?? []), ...(manifest.files ?? [])].map(e => e.target)) {
        const source = path.join(repo, rel);
        if (fs.existsSync(source) && fs.statSync(source).isFile()) {
          fs.mkdirSync(path.dirname(path.join(target, rel)), { recursive: true });
          fs.copyFileSync(source, path.join(target, rel));
        }
      }
    }
    const config = path.join(target, "src/fork/InuConfig.kt");
    fs.appendFileSync(config, "\n// unrelated verification sentinel\n");
    overlay.git(target, ["init", "--quiet"]);
    overlay.git(target, ["add", "."]);
    overlay.git(target, ["-c", "user.name=verify", "-c", "user.email=verify@example.invalid", "commit", "--quiet", "-m", "base"]);
    const host = () => new Map([...files(target)].filter(([k]) => !k.replaceAll("\\", "/").startsWith("extera-plugins/")));
    const pristine = host();
    const plan = overlay.buildPlan(target, bundle, manifest, engine, runtime);
    overlay.applyPlan(plan);
    if (!fs.readFileSync(config, "utf-8").includes("// unrelated verification sentinel")) throw new Error("sentinel lost");
    const second = overlay.buildPlan(target, bundle, manifest, engine, runtime);
    if (second.changes.length) throw new Error(`not idempotent: ${second.changes.map(c => c.relative).join(", ")}`);
    quiet(() => overlay.uninstall(target, bundle, false, engine, runtime));
    const reverted = host();
    const drift = [...new Set([...pristine.keys(), ...reverted.keys()])].filter(k => pristine.get(k) !== reverted.get(k));
    if (drift.length) throw new Error(`revert is not exact: ${drift.slice(0, 5).join(", ")}`);
    overlay.applyPlan(overlay.buildPlan(target, bundle, manifest, engine, runtime));
    fs.appendFileSync(path.join(target, "src/fork/helpers/plugins/PythonEngineBridge.kt"), "\n// external change\n");
    const before = files(target);
    let accepted = true;
    try {
      overlay.buildPlan(target, bundle, manifest, engine, runtime);
    } catch (error) {
      if (!(error instanceof overlay.InstallError)) throw error;
      accepted = false;
    }
    if (accepted) throw new Error("unknown changes were accepted");
    const after = files(target);
    if (before.size !== after.size || [...before].some(([k, v]) => after.get(k) !== v)) throw new Error("preflight wrote files");
    console.log(`${mode}: ${plan.changes.length} changes applied; repeat and conflict checks passed`);
  };

  let failed = 0;
  try {
    for (const mode of ["clean", "current-checkout"]) {
      try {
        check(mode);
      } catch (error) {
        failed++;
        console.error(`${mode}: FAILED: ${(error as Error).message}`);
      }
    }
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
  return failed;
}

if (import.meta.main) {
  const { values } = parseArgs({
    options: { repo: { type: "string" }, upstream: { type: "string" }, "runtime-upstream": { type: "string" }, pinned: { type: "boolean" } },
  });
  const repo = path.resolve(values.repo ?? path.dirname(overlay.BUNDLE));
  process.exit(verify(repo, { upstream: values.upstream, runtimeUpstream: values["runtime-upstream"], pinned: values.pinned }) ? 1 : 0);
}
