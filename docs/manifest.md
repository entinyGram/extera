# manifest

`apply.py` зливає всі `manifest/*.json` в один словник за абеткою імен файлів. списки дописуються, `known_drafts` об'єднується, будь-який інший ключ, що повторюється, дає помилку. старий одиночний `manifest.json` у корені теж читається (так влаштовані тести), але має пріоритет, тож його в пакеті бути не повинно.

## файли

| файл | ключі | вміст |
| --- | --- | --- |
| `sources.json` | `upstream`, `runtime_upstream`, `host` | url і pinned commit exteraless та chaquopy fork, commit entinyGram, під який писалась накладка |
| `vendor.json` | `vendor`, `runtime_vendor`, `vendor_edits`, `known_drafts` | що брати з upstream у `engine/`, точкові правки vendor, дозволені старі хеші |
| `overlay.json` | `files`, `resources` | копії з `overlay/` і merge рядків `strings_inu.xml` |
| `edits-gradle.json` | `edits` | `worktree/build.gradle`, `worktree/TMessagesProj_App/build.gradle` |
| `edits-fork.json` | `edits` | `src/fork/**` |
| `edits-stock.json` | `edits` | stock java у `worktree/` |
| `edits-docs.json` | `edits` | `FEATURES.md` |

## записи

| тип | поля | поведінка |
| --- | --- | --- |
| vendor | `source`, `target`, `exclude[]` | `git archive` pinned commit, префікс `source` переноситься в `target`. порожній збіг є помилкою |
| vendor_edits | `path`, `before`, `after`, `alternatives[]` | правка тексту vendor-файла до запису |
| files | `source`, `target`, `previous_sha256[]` | копія з `overlay/`. ціль з невідомим вмістом зупиняє apply |
| resources | `source`, `target` | дописує відсутні `<string name>`. той самий name з іншим значенням є конфліктом |
| edits | `path`, `before`, `after`, `alternatives[]` | рівно один збіг `before`, або вже рівно один `after` |

`after` містить `before` разом із доданим, тож revert просто замінює `after` на `before`. перенос рядків і BOM підлаштовуються під файл-ціль.

## як міняти

- редагуй json через `json.load` / `json.dump` (`indent=2`, `ensure_ascii=False`), а не текстовою заміною: рядки екрановані
- нова правка stock іде в `edits-stock.json`, fork — в `edits-fork.json`. правки одного файла тримай поруч і в порядку застосування
- змінився файл у `overlay/`, а на host лежить стара копія: додай sha256 старої в `previous_sha256`
- після змін запусти `apply.py --check`, тести і `apply.py --map > docs/hooks.md`
