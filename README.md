# extera

приватна накладка другого рушія плагінів (python, exteraless) на entinyGram. лежить у `extera-plugins/` кореня entinyGram, ignored основним repo, має свій git: `entinyGram/extera` (private).

накладка нічого не комітить у entinyGram, не чіпає stgit і `patches/`. вона пише в `src/` і `worktree/` як незакомічені зміни, а `build.py` після збірки знімає їх сам.

## quickstart

з кореня entinyGram:

| дія | команда |
| --- | --- |
| перевірити, нічого не змінюючи | `python extera-plugins/apply.py --check` |
| стан накладки | `python extera-plugins/apply.py --status` |
| накласти | `python extera-plugins/apply.py` |
| зняти | `python extera-plugins/apply.py --revert` |
| зібрати apk (накласти → зібрати → зняти) | `python extera-plugins/build.py` |
| показати збірку без запуску | `python extera-plugins/build.py --dry-run` |
| тести | `python -m unittest discover -s extera-plugins/tests` |

`--repo` за замовчуванням дорівнює батьківській папці, тож у звичайному checkout його можна не передавати. збірку запускати лише на прохання, вона йде близько 10 хвилин.

## структура

| шлях | що це | git |
| --- | --- | --- |
| `apply.py` | накладка: `--check`, `--status`, `--revert`, `--map` | так |
| `build.py` | нумерована локальна збірка `YYYYMMDDNN` | так |
| `sdk_release.py` | пакує і підписує python sdk для `SdkUpdater` | так |
| `manifest/` | що і куди накладати, розбито за областями | так |
| `overlay/` | наші файли: екрани, міст, рядки, gradle-скрипт рушія | так |
| `tests/` | unit-тести і `verify_overlay.py` | так |
| `docs/` | документація, див. нижче | так |
| `engine/` | згенерований vendor: java, python, chaquopy і копії з `overlay/engine` | ні |
| `var/` | `cache/` git-джерел, `state/` транзакцій, `out/` apk і sdk, `config.yaml` номера збірки | ні |

## docs

| файл | про що |
| --- | --- |
| [architecture](docs/architecture.md) | модулі, bridge, hooks, vendor, overlay, chaquopy runtime |
| [manifest](docs/manifest.md) | формат і карта `manifest/` |
| [hooks](docs/hooks.md) | реєстр правок host, генерується `--map` |
| [troubleshooting](docs/troubleshooting.md) | помилки apply, build і runtime |
| [forks](docs/forks.md) | NimarkoGram та інші форки: що беремо і що ні |
| [acceptance](docs/acceptance.md) | чеклист приймання на пристрої |

## статус

перша приватна збірка (`2026110601`) відхилена користувачем: зламаний дизайн, плагіни не встановлюються, краші. компіляція, r8 і підпис проходили, але на пристрої нічого не підтверджено. ця реорганізація змінює лише пакет, поведінку рушія вона не виправляє.

## походження

рушій: [exteraless](https://github.com/exteraless/exteraless) `c83765c4`, runtime: їхній chaquopy fork `eab341a4`, обидва під GPL-3.0 (`LICENSE`). java-простори `app.exteraless.plugins` і `com.exteragram.messenger.plugins` лишаються, бо це контракт сторонніх плагінів. пакет застосунку: `ua.entaytion.entinygram`.
