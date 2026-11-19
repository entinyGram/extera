import * as path from "node:path";
import { parseArgs } from "node:util";
import { BUNDLE, InstallError, hookMap, run, status, uninstall } from "./lib/overlay.ts";

const HELP = `usage: bun extera-plugins/apply.ts [--check] [--status] [--revert] [--map]
  --repo <dir>              entinyGram checkout (default: parent of this folder)
  --upstream <git>          local exteraless repository (offline)
  --runtime-upstream <git>  local chaquopy repository (offline)
  --check                   full preflight, no writes; with --revert a revert dry run
  --status                  report whether the overlay is applied
  --revert                  take the overlay out again
  --map                     print the host hook registry as markdown`;

export function main(argv = Bun.argv.slice(2)) {
  const { values } = parseArgs({
    args: argv,
    options: {
      repo: { type: "string" }, upstream: { type: "string" }, "runtime-upstream": { type: "string" },
      check: { type: "boolean" }, status: { type: "boolean" }, revert: { type: "boolean" },
      uninstall: { type: "boolean" }, map: { type: "boolean" }, help: { type: "boolean", short: "h" },
    },
  });
  if (values.help) {
    console.log(HELP);
    return 0;
  }
  const repo = path.resolve(values.repo ?? path.dirname(BUNDLE));
  try {
    if (values.map) console.log(hookMap());
    else if (values.status) status(repo, values.upstream, values["runtime-upstream"]);
    else if (values.revert || values.uninstall) uninstall(repo, BUNDLE, !!values.check, values.upstream, values["runtime-upstream"]);
    else run(repo, values.upstream, values["runtime-upstream"], !!values.check);
  } catch (error) {
    if (!(error instanceof InstallError) && !(error as NodeJS.ErrnoException).code && !(error instanceof SyntaxError)) throw error;
    console.error(`error: ${(error as Error).message}`);
    return 1;
  }
  return 0;
}

if (import.meta.main) process.exit(main());
