# архітектура

## потік

```text
manifest/ + overlay/ + pinned upstream (var/cache)
  └─ apply.py: preflight усіх змін → транзакція з бекапами (var/state)
       ├─ engine/          vendor exteraless + chaquopy runtime + overlay/engine
       ├─ src/, src/res/   наші kotlin-файли, drawables, рядки (merge за name)
       └─ worktree/        anchored-правки stock java і gradle
  └─ build.py: apply → bun run generate-grants → gradle assemble → verify → revert
```

## модулі

```text
TMessagesProj (library): stock + src/fork
  InuHooks / stock hooks / PluginsActivity
    └─ PythonEngineBridge (reflection)
TMessagesProj_App (app): src/fork-app + engine/java + engine/python + chaquopy
  PythonPluginUi / PythonMenuBridge / Python*Activity
    └─ app.exteraless.plugins.PluginsController
         └─ PythonPluginsEngine → extera_utils.plugin_loader → base_plugin / elyx_runtime
```

library не бачить app, тому `PythonEngineBridge.kt` лежить у `src/fork/helpers/plugins/` і ходить у рушій через reflection. екрани й адаптери лежать у `src/fork-app/`.

## частини

| частина | де | роль |
| --- | --- | --- |
| bridge | `overlay/src/fork/helpers/plugins/PythonEngineBridge.kt` | вхід з library: install, intent/file hooks, меню, rpc/send/updates, db gate. винятки йдуть у `FileLog`, назовні повертається `false` або `null` |
| hooks | `manifest/edits-stock.json`, `edits-fork.json` | точки виклику bridge у stock і fork, див. [hooks](hooks.md) |
| gate | `InuConfig.PYTHON_PLUGINS`, за замовчуванням off | `PluginsController.isEngineEnabled()` вимагає і gate, і внутрішній стан exteraless |
| vendor | `manifest/vendor.json` → `engine/` | 123 шляхи з exteraless і 4 з chaquopy fork, pinned commit, плюс 40 точкових `vendor_edits` |
| overlay | `overlay/`, `manifest/overlay.json` | 31 файл і 10 resource-merge. `overlay/engine/*` перекриває vendor |
| ui | `overlay/src/fork-app/` | python-екрани на `SettingsPageActivity`/`UItem`. це ще окремі екрани, а не наш `PluginRow`/`PluginInstallSheet` |
| runtime | `overlay/engine/engine.gradle`, `build_runtime.py` | збирає `chaquopy.so` і `libchaquopy_java.so` з fork-джерел і підміняє їх у chaquopy assets/jni |

## runtime

| що | значення |
| --- | --- |
| chaquopy gradle plugin | 16.1.0 |
| python | 3.13 (major/minor host python має збігатися, `CHAQUOPY_BUILD_PYTHON`) |
| cython у venv збірки | 3.0.11 |
| abi | `arm64-v8a`, `x86_64` (`ABI_FILTERS`) |
| java deps | Aliuhook 1.1.4, mvel2 2.5.2, dexmaker 2.28.6, hiddenapibypass 6.1, fragment 1.8.5 |
| pip | setuptools, PyYAML, beautifulsoup4, debugpy, lxml, packaging, pillow, requests. **не pinned** |

`engine.gradle` додає `engine/java` і `engine/python` як source dirs, виключає legacy-пакети з `src/main/java`, бере target python через `Common.PYTHON_VERSIONS`, реєструє `buildEntinyPythonRuntime<abi>` і в `doLast` підміняє бібліотеки.

## правила, що тримають накладку цілою

- **без symlink.** `apply.py` відмовляє, якщо джерело в `overlay/` або будь-який його батько є link. `engine/` — звичайні файли. gradle бачить їх через `file('../../extera-plugins/engine')`
- **все або нічого.** повний preflight перед першим записом. помилка під час запису відкочує транзакцію. бекап лягає в `var/state/<repo>/backups/`
- **ідемпотентність.** повторний apply пропускає вже застосоване. якщо в одному файлі є і `before`, і `after`, apply зупиняється
- **revert без git.** правки знімаються за `after → before`, рядки видаляються лише ті, яких немає в `HEAD`. `engine/` revert не чіпає
- **чужі зміни.** невідомий вміст цілі є помилкою, а не перезаписом. дозволені старі версії перелічені в `previous_sha256` і `known_drafts`

## sdk

`sdk_release.py <version> --key <pem>` пакує `engine/python` у `var/out/sdk/sdk-<version>.zip` і підписаний `sdk.json`. `engine/` з'являється після `apply.py`. щоб отримати лише його, без змін host, виконай `apply.py`, потім `apply.py --revert`: revert залишає `engine/`.
