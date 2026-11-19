import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location('runtime', Path(__file__).resolve().parent.parent / 'overlay/engine/build_runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimePreparationTests(unittest.TestCase):
    def test_target_extracts_only_selected_abi_and_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'target.zip'
            with zipfile.ZipFile(archive, 'w') as target:
                target.writestr('include/python3.11/Python.h', 'header')
                target.writestr('jniLibs/arm64-v8a/libpython3.11.so', b'arm64')
                target.writestr('jniLibs/x86_64/libpython3.11.so', b'x86')
            runtime.extract_target(archive, root / 'prefix', 'arm64-v8a', '3.11')
            self.assertEqual((root / 'prefix/lib/libpython3.11.so').read_bytes(), b'arm64')
            self.assertTrue((root / 'prefix/include/python3.11/Python.h').is_file())
            self.assertFalse((root / 'prefix/jniLibs').exists())

    def test_target_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'target.zip'
            with zipfile.ZipFile(archive, 'w') as target:
                target.writestr('include/../../escape', b'bad')
                target.writestr('jniLibs/arm64-v8a/libpython3.11.so', b'arm64')
            with self.assertRaises(RuntimeError):
                runtime.extract_target(archive, root / 'prefix', 'arm64-v8a', '3.11')
            self.assertFalse((root / 'escape').exists())

    def test_jni_transform_matches_upstream_build_script(self):
        code = '__PYX_EXTERN_C void example(void);\n    __pyx_insert_code_object(c_line, code);\n'
        patched = runtime.patch_cython(code)
        self.assertIn('__PYX_EXTERN_C JNIEXPORT void JNICALL example', patched)
        self.assertIn('// __pyx_insert_code_object(c_line', patched)


if __name__ == '__main__':
    unittest.main()
