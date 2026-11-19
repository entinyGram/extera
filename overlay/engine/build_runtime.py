import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


def run(*args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)


def cmake_path(sdk):
    configured = os.environ.get('CMAKE') or shutil.which('cmake')
    if configured:
        return Path(configured)
    candidates = list((sdk / 'cmake').glob('*/bin/cmake.exe' if os.name == 'nt' else '*/bin/cmake'))
    if not candidates:
        raise RuntimeError('Install Android SDK CMake or set CMAKE to its executable')
    return max(candidates, key=lambda path: tuple(int(part) for part in re.findall(r'\d+', path.parent.parent.name)))


def extract_target(archive, prefix, abi, version):
    library = f'jniLibs/{abi}/libpython{version}.so'
    with zipfile.ZipFile(archive) as source:
        members = [item for item in source.infolist() if item.filename.startswith('include/') or item.filename == library]
        if not any(item.filename == library for item in members):
            raise RuntimeError(f'{archive} does not contain {library}')
        for item in members:
            relative = Path(item.filename) if item.filename.startswith('include/') else Path('lib') / Path(library).name
            destination = (prefix / relative).resolve()
            if not destination.is_relative_to(prefix.resolve()):
                raise RuntimeError(f'Invalid target archive path: {item.filename}')
            if item.is_dir():
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source.read(item))


def patch_cython(text):
    text = re.sub(r'^__PYX_EXTERN_C (\w+)', r'__PYX_EXTERN_C JNIEXPORT \1 JNICALL', text, flags=re.MULTILINE)
    return text.replace('__pyx_insert_code_object(c_line', '// __pyx_insert_code_object(c_line')


def main():
    parser = argparse.ArgumentParser(description='Build the pinned exteraless Chaquopy runtime for Android')
    for name in ('source', 'workspace', 'output', 'target-zip', 'ndk', 'sdk'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--abi', choices=('arm64-v8a', 'x86_64'), required=True)
    parser.add_argument('--python-version', default='3.11')
    args = parser.parse_args()
    if f'{sys.version_info.major}.{sys.version_info.minor}' != args.python_version:
        parser.error(f'Set CHAQUOPY_BUILD_PYTHON to Python {args.python_version}')
    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    venv = workspace / 'venv'
    python = venv / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    if not python.exists():
        run(sys.executable, '-m', 'venv', venv)
    installed = subprocess.run([str(python), '-c', 'import Cython; assert Cython.__version__ == "3.0.11"'], capture_output=True)
    if installed.returncode:
        run(python, '-m', 'pip', 'install', 'Cython==3.0.11')
    generated = workspace / 'cython'
    generated.mkdir(exist_ok=True)
    source = args.source.resolve()
    for directory, stem in ((source / 'src/main/python/java', 'chaquopy'), (source / 'src/main/python', 'chaquopy_java')):
        output = generated / (stem + '.c')
        run(python, '-m', 'cython', '-Wextra', '-Werror', stem + '.pyx', '-I', generated, '-o', output, cwd=directory)
        output.write_text(patch_cython(output.read_text(encoding='utf-8')), encoding='utf-8', newline='\n')
    prefix = workspace / 'prefix'
    extract_target(args.target_zip, prefix, args.abi, args.python_version)
    cmake = cmake_path(args.sdk)
    ninja = cmake.parent / ('ninja.exe' if os.name == 'nt' else 'ninja')
    if not ninja.exists():
        found = shutil.which('ninja')
        if not found:
            raise RuntimeError('Install Android SDK CMake with Ninja or add ninja to PATH')
        ninja = Path(found)
    build = workspace / 'cmake'
    run(cmake, '-S', source, '-B', build, '-G', 'Ninja', f'-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}',
        '-DCMAKE_POLICY_VERSION_MINIMUM=3.5', '-DCMAKE_BUILD_TYPE=Release',
        f'-DCHAQUOPY_SRC_DIR={generated.as_posix()}',
        f'-DCMAKE_TOOLCHAIN_FILE={(args.ndk / "build/cmake/android.toolchain.cmake").as_posix()}',
        f'-DANDROID_ABI={args.abi}', '-DANDROID_NATIVE_API_LEVEL=26', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
        f'-DCHAQUOPY_PYTHON_VERSION={args.python_version}', f'-DCHAQUOPY_INCLUDE_PYTHON={(prefix / "include").as_posix()}',
        f'-DCHAQUOPY_LIB_DIRS={(prefix / "lib").as_posix()}', '-DCHAQUOPY_PY_SUFFIX=.so')
    run(cmake, '--build', build)
    for name in ('chaquopy.so', 'libchaquopy_java.so'):
        shutil.copyfile(build / name, args.output / name)


if __name__ == '__main__':
    main()
