# Playbook scaffold, package move and modules — design

Date: 2026-11-12
Status: awaiting review
Step 1 of 7 in the VaultStorage standardisation programme (see "Programme" below).

## Goal

Put VaultStorage on the paper-plugin-playbook build, move it to
`com.minecraftcitiesnetwork.vaultstorage` on Paper 26.3, remove DemocracyLib, and move every
optional-plugin integration out of the main jar into a feature module. Apart from where player
names are looked up, the plugin does what it did before. Every later step then lands on a build
that already enforces the conventions.

## Decisions already made

| Decision | Value | Source |
| --- | --- | --- |
| Group id | `com.minecraftcitiesnetwork` | user |
| Base package | `com.minecraftcitiesnetwork.vaultstorage` | group + plugin name |
| Minecraft version | Paper 26.3 | user |
| Other plugins using the API | none | user |
| Breaking release | 2.0.0; permission nodes may be renamed | user |
| Hotfix on the old code | none | user |
| Live data | must survive | 82,705 vaults in production |
| DemocracyLib | removed, the way homeland-security removed it | user |
| Optional plugins | each one is a feature module | user |

## Scope

In scope:

- A multi-project Gradle Kotlin DSL build: the host plugin and four modules, with `buildSrc`
  conventions, version catalog, Shadow, Checkstyle, `.editorconfig`, `.gitattributes`.
- `plugin.yml` replaced by `paper-plugin.yml`.
- Package move from `net.democracycraft.vault` to `com.minecraftcitiesnetwork.vaultstorage`,
  into packages named by feature.
- Paper API 1.21.8 to 26.3, with only the source changes the compiler demands.
- DemocracyLib removed; names looked up through Paper, or through the Namebook and Floodgate
  modules when they are installed.
- The module system wired into the host, and the Namebook, Floodgate, ChestShop and Realty
  auto-vault integrations moved into modules.
- The minimum code changes `paper-plugin.yml` forces (command registration, plugin metadata).
- The Bukkit `ServicesManager` registrations of `VaultService`, `WorldGuardService` and
  `BoltService` removed. Nothing outside the plugin reads them.
- CI on JDK 25. A starter `CLAUDE.md`.

Out of scope, and deliberately so:

- Every behaviour fix from the audit. They belong to steps 2 to 7.
- Renaming permission nodes or config keys, or changing tables or menu files.
- Splitting or merging classes. Files move to new packages or modules; their contents do not
  change beyond `package` and `import` lines and what the other sections list.
- Replacing Essentials mail with PlayerNotifications. Step 3 does that, where the notification
  logic is rewritten; until then Essentials stays a required dependency of the host.

## Architecture

### Build layout

```
settings.gradle.kts            rootProject.name = "vaultstorage"
gradle.properties              version=2.0.0-SNAPSHOT
gradle/libs.versions.toml      every version
buildSrc/                      three convention plugins
plugin/                        the host: VaultStorage.jar
modules/namebook-adapter/
modules/floodgate-adapter/
modules/chestshop-adapter/
modules/realty-autovault/
```

2.0.0 because the package move and the Paper bump are breaking. The version literal leaves
`build.gradle` and the hard-coded `version: '1.2.0'` leaves the descriptor.

This is not the playbook's `api` / `core` / `platform` layout. That shape is for a plugin other
plugins compile against, and none does. It is a single host project plus the module projects
that compile against it, which is how the `modules` convention is built.

Convention plugins in `buildSrc/src/main/kotlin/`:

| Convention | Applied by | Sets |
| --- | --- | --- |
| `vaultstorage-conventions` | every project | toolchain, compiler options, JUnit 5, Checkstyle, repositories |
| `vaultstorage-shared-relocation` | every project | the one list of relocations, under `com.minecraftcitiesnetwork.vaultstorage.libraries` |
| `vaultstorage-module-conventions` | modules | the two above; `compileOnly(project(":plugin"))` and the same as `testImplementation`; the shadow jar as the shipped artifact |

`gradle/libs.versions.toml`. Dependency versions are carried over from `build.gradle` unchanged
unless the 26.3 build fails with them:

| Alias | Coordinates | Version |
| --- | --- | --- |
| `paper-api` | `io.papermc.paper:paper-api` | `26.3.+` |
| `plugin-infrastructure` | `com.minecraftcitiesnetwork:plugin-infrastructure` | `1.0.0-SNAPSHOT` |
| `namebook-api` | `com.minecraftcitiesnetwork:namebook-api` | `1.0.0-SNAPSHOT` (as homeland-security) |
| `bolt-bukkit`, `bolt-common` | `org.popcraft:bolt-*` | `1.1.33` |
| `worldguard` | `com.sk89q.worldguard:worldguard-bukkit` | `7.0.17` |
| `essentialsx` | `net.essentialsx:EssentialsX` | `2.21.2` |
| `floodgate-api` | `org.geysermc.floodgate:api` | `2.2.5-SNAPSHOT` |
| `chestshop` | `com.acrobot.chestshop:chestshop` | `3.12.2` |
| `realty-paper-api` | `io.github.md5sha256:realty-paper-api` | `1.4.0` |

Also in the catalog: `java = "25"`, `minecraft = "26.3"`, `annotations`, `junit`, `checkstyle`
(current release, looked up when the file is written), `shadow`, `run-paper`. Configurate is
not added in this step; step 4 adds it.

The Gradle wrapper moves from 9.2.0 to 9.8.2. Shadow 9.4.1 and run-paper 3.1.0 are proven with
9.8.2 in GriefPreventionUI, which targets the same Paper 26.3. Tool versions (JUnit, Checkstyle,
annotations) are taken from that project too. Repositories are the
ones `build.gradle` lists today, minus jitpack (DemocracyLib was the only thing fetched from it),
plus `https://maven.minecraftcitiesnetwork.com/snapshots`.

A `Sync` task copies the module jars into `run/plugins/VaultStorage/modules/` before
`runServer`, leaving anything that is not a jar alone.

### Host and modules

The host knows three other plugins: Bolt, WorldGuard and WorldEdit (and Essentials until step
3). Every other plugin is reached through an interface the host owns and a module supplies.

`VaultStoragePlugin` shades and relocates plugin-infrastructure and owns one
`ModuleLifecycleManager<VaultStoragePlugin>` over `plugins/VaultStorage/modules/`. `start()` is
the last call in `onEnable` and `stop()` the first in `onDisable`.

The host holds one `Integrations` object with a slot per interface. Each slot starts with a
built-in default. A module fills its slot in `initialize` and restores the default in
`shutdown`. Host classes are given the `Integrations` object and read a slot each time they use
it, because modules start after the host's services are built.

| Slot | Default in the host | Filled by |
| --- | --- | --- |
| Primary name source | none | `namebook-adapter` |
| Bedrock name source | none | `floodgate-adapter` |
| Bedrock client check (`Predicate<UUID>`) | always false | `floodgate-adapter` |
| `ChestShopService` | `NoOpChestShopService` (existing class) | `chestshop-adapter` |

`realty-autovault` fills no slot. It is a whole feature: it registers its own listener and uses
the host's capture and region services.

Each module follows the `modules` convention:

- A `public final class XModule extends SimplePluginModule<VaultStoragePlugin>` that names no
  type from its optional plugin. Those live in a separate binding class, reached only after
  `isPluginEnabled(...)` and inside `try { } catch (LinkageError)`.
- A `module-manifest.yml` whose `expected-plugin-class` is
  `com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin`. `reloadable: false` for all four
  in this step; none has a config file of its own yet.
- No bundled libraries. Each compiles against the host and ships a shadow jar so that its
  references to plugin-infrastructure are rewritten to the host's relocated copy.

| Module | Package | Holds |
| --- | --- | --- |
| `namebook-adapter` | `...vaultstorage.namebook` | `NamebookModule`, `NamebookNameSource` |
| `floodgate-adapter` | `...vaultstorage.floodgate` | `FloodgateModule`, `FloodgateNames` (replaces `BedrockUniqueIdentifierRetriever`) |
| `chestshop-adapter` | `...vaultstorage.chestshop.adapter` | `ChestShopModule`, `ChestShopServiceImp` |
| `realty-autovault` | `...vaultstorage.autovault` | `AutoVaultModule`, `AutoVaultService`, `RealtyOccupantChangeListener`, `SweepFilter`, `RegionKey`, `OfflineRegionScanner` |

`realty-autovault` keeps reading the `auto-vault.*` keys from the host's `config.yml` in this
step. Step 4 gives it its own config file and makes it reloadable.

### DemocracyLib is removed

VaultStorage uses DemocracyLib for one thing: `MojangService.getName(UUID)` and
`MojangService.getUUID(String)`, at eight call sites. Its menus already use Paper's dialog API
through the plugin's own `AutoDialog`, and its database layer is its own. The removal follows
homeland-security commit `242da700`.

In the host, package `names`:

| Type | Role |
| --- | --- |
| `NameSource` | Interface: `CompletableFuture<Optional<String>> nameOf(UUID)` and `CompletableFuture<Optional<UUID>> uuidOf(String)`. Futures never complete exceptionally; a failed lookup is empty. |
| `PaperProfileNameSource` | `Bukkit.createProfile(...).update()`. Java accounts only. Takes its profile-completing function as a constructor parameter so it can be tested without a server. |
| `FallbackNameSource` | Asks a first `NameSource`, then a second when the first answers empty. |
| `Names` | What host code calls. Resolves through the `Integrations` slots, per the table below. |

| Modules loaded | Names resolve through |
| --- | --- |
| `namebook-adapter` (with or without Floodgate's) | Namebook only |
| `floodgate-adapter` only | Paper, then Floodgate |
| neither | Paper |

Namebook alone is enough because it already covers Bedrock players and matches Floodgate's
prefix and space rules. The Java-then-Bedrock order is the order `UniqueIdentifierResolver`
uses today.

The plugin exposes `names()` in place of `getMojangService()` and
`getBedrockUniqueIdentifierRetriever()`. `UniqueIdentifierResolver` keeps its one remaining job,
accepting a UUID string before trying a name. The call sites in `VaultCaptureService`,
`VaultInventoryService`, `VaultListMenu`, `VaultScanMenu`, `MenuSubcommand` and
`TransferSubcommand` change from `mojangService.getName(uuid)` to `names.nameOf(uuid)` and
unwrap the `Optional` where they tested for null.

`LoadingMenu` calls `FloodgateApi` directly today to send Bedrock clients a chat line instead of
a dialog. It reads the Bedrock client check from `Integrations` instead. The check stays
Floodgate's own rather than a UUID-format test, because a Bedrock player with a linked Java
account has a Java UUID.

Why now and not later: today `build.gradle` declares DemocracyLib but applies no Shadow plugin,
so the jar does not contain it. That only works under `plugin.yml`, where a plugin can load
classes from another plugin's jar. `paper-plugin.yml` isolates class loaders, so keeping
DemocracyLib would mean shading and relocating a library whose cross-plugin bridge has never been
tested that way, only to remove it afterwards.

### Descriptor

`plugin/src/main/resources/paper-plugin.yml`:

```yaml
name: VaultStorage
version: "${version}"
main: com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin
api-version: "26.3"
description: Stores locked containers as vaults when a region changes hands.
authors: [Alepando]

dependencies:
  server:
    Bolt:       { load: BEFORE, required: true,  join-classpath: true }
    WorldGuard: { load: BEFORE, required: true,  join-classpath: true }
    WorldEdit:  { load: BEFORE, required: true,  join-classpath: true }
    Essentials: { load: BEFORE, required: true,  join-classpath: true }
    floodgate:  { load: BEFORE, required: false, join-classpath: true }
    Namebook:   { load: BEFORE, required: false, join-classpath: true }
    ChestShop:  { load: BEFORE, required: false, join-classpath: true }
    Realty:     { load: BEFORE, required: false, join-classpath: true }

permissions:
  # the eight existing nodes, unchanged, each with description and default
```

- The plugin name stays `VaultStorage`, so the data folder, `config.yml` and `menus/` are reused.
- The four optional plugins are declared in the host's descriptor even though only modules use
  them. Module class loaders are parented to the host's, so this entry is what makes those
  plugins' classes reachable.
- Today the code calls `FloodgateApi.getInstance()` unconditionally in `onEnable` without
  declaring Floodgate, so the plugin fails to enable on a server without it. After this step it
  enables either way.
- The eight permission nodes and their defaults are copied as they are. Step 5 renames them.

### Code changes forced by `paper-plugin.yml` and 26.3

| Today | Step 1 |
| --- | --- |
| `getCommand("vault").setExecutor(cmd)`; command declared in `plugin.yml` | A `LegacyVaultCommand implements BasicCommand` wraps the existing `VaultCommand` and is registered through `LifecycleEvents.COMMANDS`. Its `permission()` returns `vaultstorage.user`. |
| `getDescription().getPermissions()` | `getPluginMeta().getPermissions()` |
| Wildcard imports in 20 of 94 files; one `printStackTrace` | Expanded to explicit imports; `printStackTrace` replaced with a `Logger` call. Checkstyle fails the build otherwise. |
| API removed or changed between 1.21.8 and 26.3 | Fixed where the compiler reports it, with the smallest change. Each one is listed in the commit message. |

`LegacyVaultCommand` is temporary. Step 5 deletes it together with the hand-rolled subcommand
framework.

### Package move

The `api` / `internal` split goes. It existed to mark what other plugins may compile against,
and no other plugin does. Below it the code is grouped by layer (`service`, `util`, `listener`,
`ui`), where the playbook groups by feature. Since every file is being moved anyway, each one
moves once, straight to its feature package, with `git mv` so `git log --follow` keeps history.

Host packages under `com.minecraftcitiesnetwork.vaultstorage`:

| Package | Holds today's |
| --- | --- |
| (root) | `VaultStoragePlugin`, `Integrations` |
| `capture` | `VaultCaptureService`, `VaultCapturePolicy`, `HangingVaultSupport`, `PlayerVaultEvent` |
| `placement` | `VaultPlacementService`, `PlayerPlaceVaultEvent` |
| `vault` | `Vault`, `VaultImp`, the DTO types, `VaultInventoryService`, `VaultAction`, `ItemSerialization`, `PlayerDeleteVaultEvent` and the event base classes |
| `scan` | `VaultScanService`, `VaultScanServiceImpl`, `VaultRegionScanner`, `ScanResult` |
| `region` | `WorldGuardService`, `WorldGuardServiceImp`, `VaultRegion`, `VaultRegionImp`, `RegionGeometry` |
| `bolt` | `BoltService`, `BoltServiceImp` |
| `chestshop` | `ChestShopService`, `NoOpChestShopService` |
| `names` | `NameSource`, `PaperProfileNameSource`, `FallbackNameSource`, `Names`, `UniqueIdentifierResolver` |
| `menu` | every dialog menu, `AutoDialog`, `Menu`, `ParentMenu`, `ChildMenu`, `VaultUIContext`, `SearchStrategy` |
| `command` | `VaultCommand`, the subcommand framework and subcommands, `LegacyVaultCommand` |
| `permission` | `VaultPermission` |
| `session` | `VaultSessionManager`, `DynamicListener` |
| `config` | `AutoYML`, `ConfigPaths`, `ConfigInitializer`, `DataFolder`, `MailConfig`, `MiniMessageUtil` |
| `database` | `MySQLManager`, `DatabaseSchema`, `AutoTable` and friends, `VaultDAO`, `VaultDAOImpl`, `VaultService`, `VaultServiceImpl`, the entity classes |

`menu`, `command` and `config` are layers rather than features. They stay as single packages
because each is small, is rewritten whole by its own step (6, 5 and 4), and would otherwise be
scattered across packages just before that rewrite. `session` and most of `database` are deleted
by steps 3 and 2.

Moving classes apart makes some package-private members unreachable. Where the compiler reports
one, its visibility is widened to `public`; nothing else about the class changes.

### Repository hygiene

- `.gitignore` gains `bin/`, `run/` and `/*.sql`.
- `.github/workflows/build.yml` installs JDK 25 instead of 21 and uploads the host jar and the
  four module jars.
- `README.md` build and install sections updated for 26.3, Java 25, the `modules/` folder and
  which module each optional plugin needs.
- `CLAUDE.md` created: build commands, Java and Minecraft versions, the host and module list,
  the feature package layout, the rule that only a module's binding class may name an optional
  plugin's types, and the programme's remaining steps.

## Conventions that apply

`project-setup`, `modules` and `code-style` (this step), `release` (version in
`gradle.properties`), `testing` (the tests below). `database`, `threading`, `configuration`,
`localisation` and `commands` apply from their own steps onward.

From this step on, every class a step creates or rewrites meets `code-style` in full:
`@NotNull` / `@Nullable` on every reference-typed parameter and return, accessor-style getters,
constructor injection, the plugin's `Logger` passed in, no `VaultStoragePlugin.getInstance()`.
Classes that are only moved keep their current style until their step, or until step 7.

## Error handling

No runtime error handling changes in the host. A module that fails to initialise is logged at
SEVERE by the module manager and skipped; the host keeps running with that slot's default. The
build gains two failure modes on purpose: Checkstyle violations fail `check`, and an unexpanded
`${version}` fails `PluginDescriptorTest`.

## Testing

Automated, in the host:

- `PluginDescriptorTest`: `paper-plugin.yml` names the main class and its version is expanded.
- `PermissionDeclarationTest`: every `VaultPermission` node is declared in `paper-plugin.yml`,
  and every declared node has an enum constant. This replaces a warning that today only appears
  in the server log.
- `PaperProfileNameSourceTest`: a completed profile yields its name and UUID; a failed or
  exceptional completion yields empty, never an exception; an invalid name yields empty.
- `NamesTest`: the three rows of the name-resolution table, and that the second source is asked
  only when the first answers empty.

Automated, in each module:

- The manifest parses, uses the kebab-case keys, and its `expected-plugin-class` equals the
  host's main class name.
- The entry class's class file contains no reference to the optional plugin's packages. It is
  scanned as bytes, as Realty's `EntryClassIsolationTest` does, because the class cannot be
  loaded in a unit test: its supertype is `compileOnly`.

`./gradlew build` must end in `BUILD SUCCESSFUL` with `checkstyleMain` and `test` executed in
every project.

Manual, on a 26.3 server with Bolt, WorldGuard, WorldEdit and Essentials installed and a copy of
the production database:

1. With no module jars: the plugin enables with no stack trace.
2. With all four module jars and their plugins: the log lists four modules started.
3. With a module jar whose plugin is missing: that module is skipped with one log line and the
   plugin still enables.
4. `/vault` opens the menu; `/vault capture` starts a session; tab completion lists subcommands.
5. An existing vault opens in view mode, shows its items, and the title shows the owner's name
   rather than a UUID. `/vault menu <name>` resolves an offline Java player in every case and an
   offline Bedrock player when the Namebook or Floodgate module is loaded.
6. Capture one container and place it back.
7. With the auto-vault module: a Realty occupant change schedules a sweep.

These cannot be automated in this step because the plugin has no seams for tests yet.

## Known limitations

- **Every audit finding is still present after this step**, including the paths that lose items
  when a database write fails. This was chosen: fixing them in code that steps 2 and 3 replace
  would mean writing the fixes twice. Until step 3 ships, a 2.0.0 snapshot is no safer than 1.2.0.
- **The jar runs on Paper 26.3 only.** There is no build for 1.21.8 from this branch onward.
- **Operators install up to five jars, seven after step 3.** The host goes in `plugins/`; each module goes in
  `plugins/VaultStorage/modules/`. An integration whose module jar is missing is silently off.
  The startup log lists the modules that started.
- **Modules compile against the host's internals.** There is no stable module API; a module jar
  must be built from the same commit as the host.
- **Dependency versions are not upgraded** unless the 26.3 build requires it. Bolt, WorldGuard,
  Essentials, ChestShop and Realty must each have a 26.3-compatible release installed on the
  server; that is an operations task outside this repository.
- **Name lookups change source.** Results should match, but a name Mojang's API knew and neither
  Namebook nor the server's profile cache knows may now show as a short UUID until resolved.
- **With neither the Namebook nor the Floodgate module, Bedrock players cannot be found by
  name.** They are still found by UUID.

## Programme

One spec and one plan per step. Each step ships a plugin that builds and runs.

| Step | Delivers | Audit findings it closes |
| --- | --- | --- |
| 1. Scaffold, package move, modules | This document | undeclared Floodgate dependency |
| 2. Persistence | `2026-11-12-persistence-design.md` | single connection and global lock; no transactions; 64 KB item limit; silent schema failures; plugin running without a database; N+1 queries |
| 3. Capture, placement, auto-vault | `2026-11-12-capture-placement-autovault-design.md` | item loss on failed writes; frozen capture session; placement duplication; capture mode surviving logout; bounding-box region checks; per-call region rebuild; mail spam; delete event on the wrong thread; main-thread queries and no paging in the vault list; scan lock-out after logout; ChestShop sign removal rule; zero-UUID owner fallback; slot positions lost; vault list filtered differently for admins and owners; displaced players unable to retrieve vaults (placement on their own GriefPrevention claim) |
| 4. Config and messages | Configurate settings records loaded once, one `messages.yml`, no player text in Java, a reload command, module config files | YAML read on every tick; defaults overwriting a broken file; hard-coded English; user text parsed as MiniMessage |
| 5. Commands and permissions | Brigadier, `vaultstorage.command.*` nodes, every node declared and checked | unchecked `action.place`; errors with no log; exception text shown to players; conflicting defaults; unrestricted scan teleport; region list visible to everyone |
| 6. Menus | Vault list with location, date and item count; delete confirmation; loading dialog that reports failure; stable scan paging | uninformative labels; stuck loading dialog; unstable entity paging |
| 7. Sweep | Every class no earlier step rewrote brought to `code-style`; the `getInstance()` singleton deleted; dead code removed (the DTO layer, the session manager's JSON, unused config keys) | none; this closes the remaining playbook gaps |

Steps 4 to 7 are outlines until steps 1 to 3 are in place.

Decisions to settle when their step is specified:

| Step | Decision |
| --- | --- |
| 4 | Do the database settings move to their own `database.yml`? |
| 4 | Is operators' existing wording in the seven `menus/*.yml` files migrated into `messages.yml`, or abandoned? |
| 5 | What do the non-command nodes become (`action.view`, `action.edit`, `action.copy`, `action.place`, `action.capture`, `admin.override`)? The playbook names only `.others` and `.bypass`. |

Deliberately left unaligned with the playbook:

- **No `deploy.yml`.** Nothing is published; no other plugin compiles against this one.
- **No live-server test tier.** The playbook makes it opt-in, and it has not been asked for.

## Working arrangement

- Branch `refactor/playbook-scaffold` from `1.21.8`, which carries three fixes `origin/master`
  does not have (`eb85e95`, `0c6de0c`, `d9e550d`). All seven steps land on branches off this
  line, and the finished programme is merged to `master` at the end.
- The uncommitted change to `VaultCaptureService.java` is left out of this work. It is stashed,
  not deleted, when the branch is created. Step 3 solves the problem it addresses a different way.
- A production database snapshot (`s80037_vaultstorage.sql`) sits in the repository root for
  testing. It must never be committed. It is listed in `.git/info/exclude` now, and this step
  adds `/*.sql` to `.gitignore`; migration scripts live under `src/main/resources`, so the
  root-only pattern does not catch them.
