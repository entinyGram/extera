# forks

з чим звіряли: android-репозиторії з поста `t.me/YuTeaching/6`, читали через `gh api` 2026-11-19 без клонування. python-рушій або патч-пакет знайшовся тільки в п'яти. решта не мають ні того, ні іншого або недоступні.

## NimarkoGram (`Ettacent/NimarkoGram`)

| що | у них | у нас | рішення |
| --- | --- | --- | --- |
| hook-рушій | Pine `216d910f` з 16KB `libpine.so` і java-guard від arm64 receiver mis-read (`pine-*.patch`) | Aliuhook 1.1.4 | кандидат. потрібна нативна збірка і перевірка на пристрої, поки не беремо |
| старт python | `PythonStartupPlatform`: sha256 bootstrap-ассетів з `build.json`, видалення пошкоджених і повторний розпак | `AndroidPlatform` | беремо наступним кроком: мало коду, б'є по крашах на старті |
| runtime | `libs/chaquopy-runtime/manifest.json`: upstream, revision, sha256 патча, target zip і кожного `.so` | шляхи і версії в `engine.gradle` | беремо ідею: винести pin і sha256 у `manifest/sources.json` |
| python | 3.11, chaquopy 17.0.0, hybrid arm64+armv7, `-PngArm64Only` | 3.13, chaquopy 16.1.0, arm64/x86_64 | лишаємо 3.13, бо з ним проходила збірка. 3.11 — окремий експеримент разом зі зміною wheels |
| wheels | `--find-links python-wheels/arm64-v8a` (16KB), `--constraint python-constraints.txt` | pip без pin | беремо: constraints і, за потреби, локальні wheels |
| dev install | `PluginDevInstallBridge`, токен і окремий потік | `dev_server.py` з vendor | не беремо, це dev-інструмент |
| pip на пристрої | `pip/PipController.java` (293 KB) | `pip_controller.py` з vendor | не розібрано, `[INFERENCE]` дублює vendor |
| ui | власні `PluginsActivity`/`PluginSettingsActivity` | наші inu-екрани | не беремо: вимога — наш дизайн |
| збірка | `docs/BUILDING.md`, патчі окремими файлами в `patches/` | `README.md`, `manifest/` | формат схожий, ми вже маємо `--check` і `--revert` |

## інші

| форк | що знайшли | ідея |
| --- | --- | --- |
| NebulaGram (`BBGGVP5/NebulaGram`) | `apply-overlay.sh`: reset upstream, потім `cp overlay/`, потім `patches/android/0001-*.patch` (290 шт.). `prepare-android-tree.py` відмовляє на symlink в overlay і пише sha256 кожного входу. `HOOKS.md`: реєстр «патч → файл → якір → призначення» | **взяли**: заборону symlink і реєстр hooks (`--map`). reset worktree нам не підходить: його ділять кілька агентів |
| LinkiGram (`timaa130704/LinkiGram`) | лінія Nimarko: python 3.11, `python-wheels/arm64-v8a` (cryptography, numpy, regex, libxml2) | wheels у repo дають збірку без мережі |
| PrimeGram (`Primeevokak/PrimeGram`) | 3.11, pin native-пакетів з поясненням (без pin pip бере sdist і падає на windows), `dexmaker`, `_prime_pip.py` для чистих пакетів під час роботи | pin native pip-пакетів. dexmaker у нас уже є |
| amegram (`fuckramochka/amegram`) | 3.12, пояснення конфлікту `splits.abi` з chaquopy, `heroku_compat/` (loader, pip_runner) | `[INFERENCE]` шар сумісності з іншим форматом модулів, поки лише записано |

## наступні кроки (не зроблено)

1. `PythonStartupPlatform` у `overlay/engine/java` і перемкнути `ensureStarted` на нього
2. pin pip через `constraints.txt` версіями з останньої успішної збірки: їх треба взяти з build-логу, а не вигадувати
3. pin і sha256 runtime у `manifest/sources.json`
4. видима причина `startFailed` у ui замість тихого `false`
5. python-плагіни в нашому `PluginsActivity`/`PluginInstallSheet` замість окремих екранів
