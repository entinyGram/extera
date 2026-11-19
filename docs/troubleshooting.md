# troubleshooting

## apply

| помилка | причина | що робити |
| --- | --- | --- |
| `unknown existing content: <path>` | файл host відрізняється від копії в `overlay/` (зазвичай після merge) | перенести зміну host у файл `overlay/`, додати sha256 host-версії в `previous_sha256` |
| `anchor conflict in <path>: before=0, after=0` | stock-рядок зсунувся | оновити `before` і `after` під новий текст, зберігши наші додані рядки |
| `both applied and unapplied anchors exist` | файл наполовину накладений або правку продубльовано | `--revert --check`, потім розібрати файл руками |
| `overlay sources must be regular files, not links` | у `overlay/` потрапив symlink або junction | замінити його звичайною копією |
| `manifest key X repeats` | один верхній ключ є у двох `manifest/*.json` | лишити ключ в одному файлі |
| `file changed after preflight` | інший агент писав у той самий файл | повторити, коли файл звільниться |

`--check` зупиняється на першій зламаній правці. щоб знайти всі за один прохід, прожени кожну пару `before`/`after` з `manifest/edits-*.json` проти host коротким скриптом.

## revert

| симптом | що робити |
| --- | --- |
| `<path> changed after the overlay wrote it` | файл правили руками: перенести правку в `overlay/` або видалити файл самому |
| `file is not in HEAD` для рядків | ресурс-ціль не закомічена, revert не може визначити, які рядки наші. розібрати руками |
| після невдалої збірки зник tracked fork-файл (`PluginXposed.kt`) | `git checkout -- <path>` у entinyGram |
| висячі посилання в `worktree/.../res` | revert прибирає лише ті, що вказують на видалені файли. решту відновлює `bun` setup |

## build

| симптом | причина | що робити |
| --- | --- | --- |
| `Python plugins require ABI_FILTERS=arm64-v8a or x86_64` | інший abi | `--abi arm64-v8a` |
| `Chaquopy has no Python 3.13 runtime` | інша версія chaquopy | тримати 16.1.0 |
| `OsmdroidMap.kt not found` у `compileReleaseKotlin` | старі kotlin compile daemons бачать застарілий symlink | зупинити `kotlin-compiler-embeddable` процеси, запущені раніше, `GradleDaemon` не чіпати |
| `APK already exists` | номер уже зайнятий | перевірити `var/config.yaml` |
| `config.yaml.lock` лишився | процес убили | переконатися, що збірка не йде, видалити lock |
| pip зібрав інший `lxml`/`pillow` | pip не pinned | див. [forks](forks.md), пункт про wheels |

## runtime (на пристрої)

рядки для пошуку в логах: `FATAL EXCEPTION`, `Caused by`, `PluginsEngine: failed to start Python`, `PyException`, `Traceback`, `UnsatisfiedLinkError`, `ClassNotFoundException`, `NoSuchMethodException`, `PluginInstallHelper`, `PluginsController`.

- bridge ковтає винятки і повертає `false`, тому «нічого не сталося» часто означає виняток у `FileLog`
- `PythonPluginsEngine.ensureStarted` після першої невдачі виставляє `startFailed` і далі тихо повертає той самий результат. причину шукай у першому старті процесу
- логи рушія пишуться в `plugin_logs/plugins.log` і `plugins.1.log`. `PluginToggleTrace` у release вимкнений
- `.py` у `PluginInstallHelper.EXTENSIONS` уже додано (`PLUGIN_EXT_PY`). на пристрої це не перевірено
- inu picker і зовнішній `ACTION_VIEW` при вимкненому gate отримують `false` ще в bridge, а python picker доходить до helper
