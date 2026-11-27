# Config and messages — design

Date: 2026-11-12
Status: implemented; the two decisions below were taken by default and are open to change
Step 4 of 7. Depends on step 3.

## Goal

Settings are read once into typed records and can be reloaded; every sentence a player reads
lives in one `messages.yml`. Nothing is parsed from disk while the server ticks.

## Audit findings this step closes

- Each menu's YAML was read and parsed from disk on every use, including every action-bar tick.
- A YAML file that failed to parse was overwritten with defaults, losing the operator's edits.
- Player-facing text was partly in nine YAML files and partly hard-coded English in Java.
- Text a player typed was substituted into MiniMessage before parsing.

## Decisions taken by default

Both were listed as open in the step 1 spec. Nobody was asked; say so if either is wrong.

| Decision | Default taken | Why |
| --- | --- | --- |
| Do database settings move to `database.yml`? | Yes, and existing `mysql.*` values are imported once | It is the layout Namebook, PlayerNotifications and Friends use. Importing means an upgrade needs no re-typing of credentials. |
| Is operators' wording in `menus/*.yml` and `mail/mail.yml` migrated? | No. The old files are left on disk, untouched and unread | The keys do not map one to one, and a wrong automatic mapping would be worse than a visible reset. Startup logs once that the old files are no longer read. |

## Files

| File | Record | Holds |
| --- | --- | --- |
| `database.yml` | `DatabaseSettings` | `url` (without `jdbc:`), `username`, `password`. Not reloadable. |
| `settings.yml` | `PluginSettings` | `scan.batch-size`, `scan.cache-ttl-seconds`, `scan.cooldown-seconds`, `vault-list.page-size` |
| `messages.yml` | `MessageContainer` | every player-facing sentence |
| `modules/realty-autovault/config.yml` | `AutoVaultSettings` | `enabled`, `delay-seconds`, `include-hangings`, `notify-occupant`; the module is `reloadable: true` |
| `defaults/<name>.yml` | none | the bundled default of each host file, rewritten on every start, never read |

`config.yml` is no longer read. It is left where it is.

### Import of the old settings

On the first start with no `database.yml`, if `config.yml` exists and has `mysql.host`, the new
file is written from the bundled default with `url`, `username` and `password` filled from
`mysql.host`, `mysql.port`, `mysql.database`, `mysql.user` and `mysql.password`. This is the one
time the plugin writes an operator file that is not simply the bundled default, and it only ever
creates the file; it never rewrites one that exists. `settings.yml` and the auto-vault module's
`config.yml` are filled the same way from `scan.*` and `auto-vault.*`, with `delay-ticks`
divided by 20 into `delay-seconds`.

## Architecture

- `config.ConfigFiles`: copies the bundled default when the operator's file is absent, rewrites
  `defaults/<name>.yml`, loads with Configurate (`NodeStyle.BLOCK`), and warns about keys the
  default has and the operator's file lacks (`ConfigKeyGaps`).
- `config.LegacyConfigImport`: pure functions from the old `config.yml` values to the text of
  the new files. No Bukkit types, so it is unit tested.
- `VaultStoragePlugin` owns one `AtomicReference<PluginSettings>` and hands the reference to the
  classes that read it. `DatabaseSettings` is read once.
- `reload()` parses `settings.yml` and `messages.yml` off the main thread into new objects, and
  swaps both only if both parsed. Modules marked reloadable are then reloaded.
- `MessageContainer` (plugin-infrastructure) holds the messages; `MessageKeys` has one constant
  per key; runtime strings go in through `MessageContainer.value`, never through string
  replacement before parsing.
- `AutoYML`, `ConfigPaths`, `ConfigInitializer`, `DataFolder`, `MailConfig`, `MiniMessageUtil`
  and every nested `Config` class in the menus are deleted.

## Testing

- Each bundled file parses into its record (`settings.yml`, `database.yml`, the module's
  `config.yml`).
- `ConfigKeyGaps` reports a key missing from the operator's file and nothing else.
- `LegacyConfigImport`: host, port and database become `mariadb://host:port/database`; an
  absent old file yields the bundled default unchanged; ticks become seconds.
- `MessageKeysTest`: every constant has a key in the shipped `messages.yml` and every key has a
  constant.
- The live tests keep passing with the wording unchanged, which is what shows the conversion
  kept every message.

## Known limitations

- **Operators' custom wording is not carried over.** They re-apply it in `messages.yml`.
- **`config.yml` and the old menu files stay on disk** until an operator deletes them.
- **There is one language.** No per-player locale.
- **Yes/no and filter names are still inserted into a sentence** (`scan.vaultable-yes`,
  `placement.place-yes`, `scan.filter-*`), so an operator cannot reorder them. Step 6 rewrites the
  menus and gives each case its own key.
- **Menus, commands and the capture service reach messages through the static `Text` bridge.**
  They take a `MessageContainer` in their constructor when steps 5 to 7 rewrite them.
- **`scan.batch-size` keeps the 1.x default of 50.**

## As built

Two limitations above no longer hold: step 6 made the yes/no cases whole sentences, and step 7
replaced the static `Text` bridge with an instance the plugin owns.
