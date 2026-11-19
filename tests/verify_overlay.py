import argparse
import json
from pathlib import Path
import shutil
import tempfile

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import apply as install  # noqa: E402


def verify(repo, upstream, runtime_upstream):
    bundle = Path(__file__).resolve().parent.parent
    manifest = install.load_manifest(bundle)
    host_paths = {entry['path'] for entry in manifest['edits']}
    host_paths.update(entry['target'] for entry in manifest['resources'])
    with tempfile.TemporaryDirectory(prefix='entiny-overlay-verify-') as temporary:
        for mode in ('clean', 'current-checkout'):
            target = Path(temporary) / mode
            target.mkdir()
            for relative in host_paths:
                destination = target / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                if mode == 'current-checkout':
                    shutil.copyfile(repo / relative, destination)
                else:
                    stock = relative.startswith('worktree/')
                    source = repo / 'worktree' if stock else repo
                    path = relative.removeprefix('worktree/') if stock else relative
                    revision = manifest['host']['stock_commit' if stock else 'commit']
                    destination.write_bytes(install.git(source, 'show', revision + ':' + path))
            if mode == 'current-checkout':
                managed = {entry['target'] for entry in manifest['vendor']} | {entry['target'] for entry in manifest['files']}
                for relative in managed:
                    source = repo / relative
                    if source.is_file():
                        destination = target / relative
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(source, destination)
            config = target / 'src/fork/InuConfig.kt'
            with config.open('ab') as stream:
                stream.write(b'\n// unrelated verification sentinel\n')
            plan = install.build_plan(target, bundle, manifest, upstream, runtime_upstream)
            install.apply_plan(plan)
            assert b'// unrelated verification sentinel' in config.read_bytes()
            second = install.build_plan(target, bundle, manifest, upstream, runtime_upstream)
            assert not second.changes, [change.relative for change in second.changes]
            conflict = target / 'src/fork/helpers/plugins/PythonEngineBridge.kt'
            conflict.write_bytes(conflict.read_bytes() + b'\n// external change\n')
            before = {file.relative_to(target): file.read_bytes() for file in target.rglob('*') if file.is_file()}
            try:
                install.build_plan(target, bundle, manifest, upstream, runtime_upstream)
            except install.InstallError:
                pass
            else:
                raise AssertionError('Unknown changes were accepted')
            after = {file.relative_to(target): file.read_bytes() for file in target.rglob('*') if file.is_file()}
            assert before == after
            print(f'{mode}: {len(plan.changes)} changes applied; repeat and conflict checks passed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Check the real overlay in isolated baseline and current-checkout fixtures, without building Android')
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--runtime-upstream', type=Path, required=True)
    args = parser.parse_args()
    verify(args.repo.resolve(), args.upstream.resolve(), args.runtime_upstream.resolve())
