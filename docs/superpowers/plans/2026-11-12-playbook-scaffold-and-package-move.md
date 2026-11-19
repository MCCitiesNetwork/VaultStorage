# Playbook Scaffold, Package Move and Modules Implementation Plan

**Goal:** Put VaultStorage on the playbook build at `com.minecraftcitiesnetwork.vaultstorage` on Paper 26.3, without DemocracyLib, with every optional-plugin integration in a feature module.
**Spec:** `docs/superpowers/specs/2026-11-12-playbook-scaffold-and-package-move-design.md`
**Status:** executed. Where the code differs from a task, the spec's "As built" section says how.

Conventions used throughout:

- `P` means `plugin/src/main/java/com/minecraftcitiesnetwork/vaultstorage`.
- `T` means `plugin/src/test/java/com/minecraftcitiesnetwork/vaultstorage`.
- `BASE` means the package `com.minecraftcitiesnetwork.vaultstorage`.
- Every task ends with `./gradlew build` ending in `BUILD SUCCESSFUL`, then a commit.
- New classes meet `code-style` in full. Moved classes are not restyled.

One deviation from the spec, found while writing this plan: the Gradle wrapper moves from 9.2.0
to 9.8.2. Shadow 9.4.1 and run-paper 3.1.0 are proven with 9.8.2 in GriefPreventionUI, which
targets the same Paper 26.3; that combination has not been tried on 9.2.0.

## Task 1: Branch and repository hygiene

**Files:** modify `.gitignore`; create `.gitattributes`

- [x] `git stash push -m "pre-refactor: hang.remove before async save" -- src/main/java/net/democracycraft/vault/internal/service/VaultCaptureService.java`
- [x] `git switch -c refactor/playbook-scaffold 1.21.8`
- [x] Append to `.gitignore`:
  ```text
  bin/
  run/
  /*.sql
  ```
- [x] Create `.gitattributes`:
  ```text
  * text=auto eol=lf
  *.bat text eol=crlf
  ```
- [x] Run `git status --short` and expect only `.gitignore`, `.gitattributes` and `docs/`. `s80037_vaultstorage.sql` must not appear.
- [x] Commit: `chore: ignore run output and root sql dumps` (include `docs/superpowers/`)

## Task 2: Kotlin DSL build with conventions, on Paper 26.3

**Files:** delete `build.gradle`, `settings.gradle`; create `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `buildSrc/settings.gradle.kts`, `buildSrc/build.gradle.kts`, `buildSrc/src/main/kotlin/vaultstorage-conventions.gradle.kts`, `buildSrc/src/main/kotlin/vaultstorage-shared-relocation.gradle.kts`, `plugin/build.gradle.kts`, `config/checkstyle/checkstyle.xml`, `.editorconfig`; move `src/` to `plugin/src/`

**Interfaces:** Gradle project `:plugin`; conventions `vaultstorage-conventions`, `vaultstorage-shared-relocation`; catalog aliases named below.

- [x] Upgrade the wrapper while the old build still works: `./gradlew wrapper --gradle-version 9.8.2`
- [x] `git mv src plugin/src` and `git rm build.gradle settings.gradle`
- [x] `settings.gradle.kts`:
  ```kotlin
  rootProject.name = "vaultstorage"

  include("plugin")
  ```
- [x] `gradle.properties`:
  ```properties
  version=2.0.0-SNAPSHOT
  ```
- [x] `gradle/libs.versions.toml`:
  ```toml
  [versions]
  java = "25"
  minecraft = "26.3"
  paper-api = "26.3.+"
  annotations = "24.1.0"
  junit = "5.14.3"
  checkstyle = "14.4.0"
  shadow = "9.4.1"
  run-paper = "3.1.0"
  plugin-infrastructure = "1.0.0-SNAPSHOT"
  namebook-api = "1.0.0-SNAPSHOT"
  democracylib = "e5ddf4ac24"
  bolt = "1.1.33"
  worldguard = "7.0.17"
  essentialsx = "2.21.2"
  floodgate-api = "2.2.5-SNAPSHOT"
  chestshop = "3.12.2"
  realty-paper-api = "1.4.0"

  [libraries]
  shadow-gradle-plugin = { module = "com.gradleup.shadow:shadow-gradle-plugin", version.ref = "shadow" }
  paper-api = { module = "io.papermc.paper:paper-api", version.ref = "paper-api" }
  annotations = { module = "org.jetbrains:annotations", version.ref = "annotations" }
  junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
  junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
  junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
  plugin-infrastructure = { module = "com.minecraftcitiesnetwork:plugin-infrastructure", version.ref = "plugin-infrastructure" }
  namebook-api = { module = "com.minecraftcitiesnetwork:namebook-api", version.ref = "namebook-api" }
  democracylib = { module = "com.github.MCCitiesNetwork:DemocracyLib", version.ref = "democracylib" }
  bolt-bukkit = { module = "org.popcraft:bolt-bukkit", version.ref = "bolt" }
  bolt-common = { module = "org.popcraft:bolt-common", version.ref = "bolt" }
  worldguard = { module = "com.sk89q.worldguard:worldguard-bukkit", version.ref = "worldguard" }
  essentialsx = { module = "net.essentialsx:EssentialsX", version.ref = "essentialsx" }
  floodgate-api = { module = "org.geysermc.floodgate:api", version.ref = "floodgate-api" }
  chestshop = { module = "com.acrobot.chestshop:chestshop", version.ref = "chestshop" }
  realty-paper-api = { module = "io.github.md5sha256:realty-paper-api", version.ref = "realty-paper-api" }

  [plugins]
  run-paper = { id = "xyz.jpenilla.run-paper", version.ref = "run-paper" }
  ```
- [x] `buildSrc/settings.gradle.kts`:
  ```kotlin
  // buildSrc reads the root catalog, so the Shadow version still lives there.
  dependencyResolutionManagement {
      versionCatalogs {
          create("libs") {
              from(files("../gradle/libs.versions.toml"))
          }
      }
  }
  ```
- [x] `buildSrc/build.gradle.kts`:
  ```kotlin
  plugins {
      `kotlin-dsl`
  }

  repositories {
      mavenCentral()
      gradlePluginPortal()
  }

  dependencies {
      // Puts Shadow on every build script's classpath, so the relocation convention can
      // configure shadowJar. Projects therefore apply com.gradleup.shadow without a version.
      implementation(libs.shadow.gradle.plugin)
  }
  ```
- [x] `buildSrc/src/main/kotlin/vaultstorage-conventions.gradle.kts`:
  ```kotlin
  plugins {
      java
      checkstyle
  }

  // Precompiled script plugins cannot use the generated `libs` accessors.
  val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

  group = "com.minecraftcitiesnetwork"
  version = providers.gradleProperty("version").get()

  val targetJavaVersion = libs.findVersion("java").get().requiredVersion.toInt()

  java.toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))

  repositories {
      mavenCentral()
      maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
      maven("https://repo.codemc.io/repository/maven-public/") { name = "codemc" }
      maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
      maven("https://repo.essentialsx.net/releases/") { name = "essentialsx" }
      maven("https://repo.essentialsx.net/snapshots/") { name = "essentialsx-snapshots" }
      maven("https://repo.opencollab.dev/main/") { name = "opencollab" }
      maven("https://repo.minebench.de/") { name = "minebench" }
      maven("https://maven.democracycraft.net/releases") { name = "democracycraft" }
      maven("https://maven.minecraftcitiesnetwork.com/snapshots") { name = "mccities-snapshots" }
      // Removed in Task 5 together with DemocracyLib.
      maven("https://jitpack.io") {
          name = "jitpack"
          content { includeGroupByRegex("com\\.github\\.MCCitiesNetwork.*") }
      }
  }

  dependencies {
      compileOnly(libs.findLibrary("annotations").get())
      testImplementation(platform(libs.findLibrary("junit-bom").get()))
      testImplementation(libs.findLibrary("junit-jupiter").get())
      testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
  }

  checkstyle {
      toolVersion = libs.findVersion("checkstyle").get().requiredVersion
      configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
  }

  tasks {
      test {
          useJUnitPlatform()
      }

      withType(JavaCompile::class) {
          options.release.set(targetJavaVersion)
          options.encoding = "UTF-8"
          options.isFork = true
          options.isDeprecation = true
      }
  }
  ```
- [x] `buildSrc/src/main/kotlin/vaultstorage-shared-relocation.gradle.kts`:
  ```kotlin
  plugins {
      id("com.gradleup.shadow")
  }

  // Every package the host shades is relocated here and nowhere else. Modules apply this same
  // convention, so their references land where the host put the classes.
  val prefix = "com.minecraftcitiesnetwork.vaultstorage.libraries"

  tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
      listOf<String>(
          // Task 6 adds "com.minecraftcitiesnetwork.pluginInfrastructure".
      ).forEach {
          relocate(it, "$prefix.$it")
      }
  }
  ```
- [x] `plugin/build.gradle.kts`:
  ```kotlin
  plugins {
      `java-library`
      id("vaultstorage-conventions")
      id("com.gradleup.shadow")
      id("vaultstorage-shared-relocation")
      alias(libs.plugins.run.paper)
  }

  dependencies {
      compileOnly(libs.paper.api)
      // compileOnly, not implementation: today's jar does not contain DemocracyLib either.
      // Task 5 removes it.
      compileOnly(libs.democracylib)
      compileOnly(libs.bolt.bukkit)
      compileOnly(libs.bolt.common)
      compileOnly(libs.worldguard)
      compileOnly(libs.essentialsx) {
          exclude(group = "org.spigotmc", module = "spigot-api")
      }
      compileOnly(libs.floodgate.api)
      compileOnly(libs.chestshop)
      compileOnly(libs.realty.paper.api) { isTransitive = false }
      // compileOnly is not on the test runtime classpath.
      testRuntimeOnly(libs.paper.api)
  }

  tasks {
      build {
          dependsOn(shadowJar)
      }

      jar {
          archiveClassifier = "unshaded"
      }

      shadowJar {
          archiveBaseName = "VaultStorage"
          archiveClassifier = ""
          mergeServiceFiles()
      }

      processResources {
          val projectVersion = version
          inputs.property("version", projectVersion)
          filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
              expand("version" to projectVersion)
          }
      }

      runServer {
          minecraftVersion(libs.versions.minecraft.get())
      }
  }
  ```
- [x] In `plugin/src/main/resources/plugin.yml` change `version: '1.2.0'` to `version: "${version}"`.
- [x] Copy the playbook's files unchanged:
  ```bash
  REF=~/.claude/plugins/cache/paper-plugin-playbook/paper-plugin-playbook/0.1.0/skills/project-setup/references
  mkdir -p config/checkstyle
  cp "$REF/checkstyle.xml" config/checkstyle/checkstyle.xml
  cp "$REF/editorconfig.txt" .editorconfig
  ```
- [x] Run `./gradlew :plugin:compileJava` and expect FAIL or deprecation output from the 1.21.8 to 26.3 change. Fix each compiler error with the smallest change and keep a list for the commit message. If it compiles clean, record that.
- [x] Run `./gradlew :plugin:checkstyleMain` and expect FAIL: wildcard imports in 20 files, `printStackTrace` in `TransferSubcommand`.
- [x] Implement: in each file Checkstyle names, replace every `import x.y.*;` with one explicit import per type the file uses from that package. In `TransferSubcommand`, replace `ex.printStackTrace();` with nothing, and change the preceding `plugin.getLogger().warning("[TransferSubcommand] Transfer error ... " + ex.getMessage());` to
  ```java
  plugin.getLogger().log(Level.WARNING,
          "[TransferSubcommand] Transfer error for vault " + vaultId + " to " + newOwnerUUID, ex);
  ```
  with `import java.util.logging.Level;`.
- [x] Run `./gradlew :plugin:checkstyleMain` and expect PASS.
- [x] Run `./gradlew build`. Check `plugin/build/libs/VaultStorage-2.0.0-SNAPSHOT.jar` exists and `unzip -p` of its `plugin.yml` shows `version: "2.0.0-SNAPSHOT"`.
- [x] Commit: `build: move to the playbook Gradle build on Paper 26.3` with the list of API fixes in the body.

## Task 3: Move to `com.minecraftcitiesnetwork.vaultstorage`, by feature

**Files:** every file under `plugin/src/main/java/net/democracycraft/vault/` moves to `P/<package>/`; modify `plugin/src/main/resources/plugin.yml`

**Interfaces:** the package of every class, per the mapping below. Later tasks rely on these names.

- [x] Save this mapping as `/tmp/vs-move.txt` (old path relative to `net/democracycraft/vault`, then the new package relative to `BASE`; `.` is the root):
  ```text
  VaultStoragePlugin.java .
  api/service/Service.java .
  internal/service/VaultCaptureService.java capture
  internal/security/VaultCapturePolicy.java capture
  internal/util/hanging/HangingVaultSupport.java capture
  api/event/PlayerVaultEvent.java capture
  internal/service/VaultPlacementService.java placement
  api/event/PlayerPlaceVaultEvent.java placement
  api/convertible/Vault.java vault
  api/convertible/DTOConvertible.java vault
  internal/mappable/VaultImp.java vault
  api/data/Dto.java vault
  api/data/ItemDto.java vault
  api/data/LocationDto.java vault
  api/data/VaultDto.java vault
  internal/data/ItemDtoImp.java vault
  internal/data/LocationDtoImp.java vault
  internal/data/VaultDtoImp.java vault
  internal/service/VaultInventoryService.java vault
  internal/ui/VaultAction.java vault
  internal/util/item/ItemSerialization.java vault
  internal/store/VaultMemoryStore.java vault
  api/event/PlayerDeleteVaultEvent.java vault
  api/event/PlayerVaultStorageEvent.java vault
  api/event/VaultStorageEvent.java vault
  api/service/VaultScanService.java scan
  internal/service/VaultScanServiceImpl.java scan
  internal/util/scan/VaultRegionScanner.java scan
  api/data/ScanResult.java scan
  api/service/WorldGuardService.java region
  internal/service/WorldGuardServiceImp.java region
  api/region/VaultRegion.java region
  internal/region/VaultRegionImp.java region
  internal/util/region/RegionGeometry.java region
  api/service/BoltService.java bolt
  internal/service/BoltServiceImp.java bolt
  api/service/ChestShopService.java chestshop
  internal/service/NoOpChestShopService.java chestshop
  internal/service/ChestShopServiceImp.java chestshop/adapter
  internal/util/uuid/UniqueIdentifierResolver.java names
  internal/session/BedrockUniqueIdentifierRetriever.java floodgate
  internal/service/AutoVaultService.java autovault
  internal/listener/RealtyOccupantChangeListener.java autovault
  internal/util/region/SweepFilter.java autovault
  internal/util/region/RegionKey.java autovault
  internal/util/scan/OfflineRegionScanner.java autovault
  api/ui/AutoDialog.java menu
  api/ui/ChildMenu.java menu
  api/ui/Menu.java menu
  api/ui/ParentMenu.java menu
  internal/ui/ChildMenuImp.java menu
  internal/ui/ParentMenuImp.java menu
  internal/ui/LoadingMenu.java menu
  internal/ui/SearchStrategy.java menu
  internal/ui/VaultActionMenu.java menu
  internal/ui/VaultCaptureMenu.java menu
  internal/ui/VaultListMenu.java menu
  internal/ui/VaultPlacementMenu.java menu
  internal/ui/VaultRegionListMenu.java menu
  internal/ui/VaultScanMenu.java menu
  internal/ui/VaultUIContext.java menu
  internal/command/VaultCommand.java command
  internal/command/framework/CommandContext.java command
  internal/command/framework/Subcommand.java command
  internal/command/impl/AdminSubcommand.java command
  internal/command/impl/CaptureSubcommand.java command
  internal/command/impl/MenuSubcommand.java command
  internal/command/impl/OpenSubcommand.java command
  internal/command/impl/TransferSubcommand.java command
  internal/security/VaultPermission.java permission
  internal/session/VaultSessionManager.java session
  internal/util/listener/DynamicListener.java session
  internal/util/yml/AutoYML.java config
  internal/util/config/ConfigPaths.java config
  internal/util/config/ConfigInitializer.java config
  internal/util/config/DataFolder.java config
  internal/config/MailConfig.java config
  internal/util/minimessage/MiniMessageUtil.java config
  internal/database/MySQLManager.java database
  internal/database/DatabaseSchema.java database
  internal/database/table/AbstractTable.java database
  internal/database/table/AutoTable.java database
  internal/database/table/Column.java database
  api/dao/VaultDAO.java database
  internal/database/dao/VaultDAOImpl.java database
  api/service/VaultService.java database
  internal/service/VaultServiceImpl.java database
  internal/database/entity/VaultEntity.java database
  internal/database/entity/VaultItemEntity.java database
  internal/database/entity/VaultMemberEntity.java database
  internal/database/entity/VaultOwnerEntity.java database
  internal/database/entity/VaultRegionEntity.java database
  internal/database/entity/VaultRegionLinkEntity.java database
  internal/database/entity/WorldEntity.java database
  ```
  `ChestShopServiceImp`, `BedrockUniqueIdentifierRetriever` and the five auto-vault classes take the package they will have inside their module, so Tasks 7 to 9 move a directory and change no `package` line.
- [x] Check the mapping is complete: `wc -l < /tmp/vs-move.txt` must print `94`, the number of `.java` files.
- [x] Run the move from the repository root:
  ```bash
  OLD=plugin/src/main/java/net/democracycraft/vault
  NEW=plugin/src/main/java/com/minecraftcitiesnetwork/vaultstorage
  BASE=com.minecraftcitiesnetwork.vaultstorage
  : > /tmp/vs-sed.txt
  while read -r path pkg; do
    class=$(basename "$path" .java)
    oldpkg=net.democracycraft.vault$( [ "$(dirname "$path")" = "." ] || echo ".$(dirname "$path" | tr / .)")
    newpkg=$BASE$( [ "$pkg" = "." ] || echo ".$(echo "$pkg" | tr / .)")
    dir=$NEW$( [ "$pkg" = "." ] || echo "/$pkg")
    mkdir -p "$dir" && git mv "$OLD/$path" "$dir/$class.java"
    # Rewrites imports and fully qualified uses of this class everywhere.
    # [[:<:]] and [[:>:]] are word boundaries in macOS sed; on Linux use \\b and sed -i without ''.
    echo "s/[[:<:]]${oldpkg//./\\.}\\.${class}[[:>:]]/${newpkg}.${class}/g" >> /tmp/vs-sed.txt
    # Rewrites this file's own package line.
    sed -i '' -E "s/^package ${oldpkg//./\\.};/package ${newpkg};/" "$dir/$class.java"
  done < /tmp/vs-move.txt
  find "$NEW" -name '*.java' -print0 | xargs -0 sed -i '' -E -f /tmp/vs-sed.txt
  ```
- [x] In `plugin.yml` set `main: com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin`.
- [x] Run `./gradlew :plugin:compileJava` and expect FAIL: classes that shared a package no longer do, so they need imports, and a few package-private members are now out of reach.
- [x] Implement: for each "cannot find symbol", add the explicit import for the class at its new package. For each "is not public ... cannot be accessed from outside package", make that member `public`. Change nothing else. Then delete every `import` Checkstyle reports as unused.
- [x] Run `grep -rn "net\.democracycraft\.vault" plugin/src` and expect no output. (`net.democracycraft.democracyLib` imports remain until Task 5.)
- [x] Run `./gradlew build`.
- [x] Commit: `refactor: move to com.minecraftcitiesnetwork.vaultstorage, grouped by feature`

## Task 4: `paper-plugin.yml` and command registration

**Files:** delete `plugin/src/main/resources/plugin.yml`; create `plugin/src/main/resources/paper-plugin.yml`, `P/command/LegacyVaultCommand.java`, `T/PluginDescriptorTest.java`, `T/permission/PermissionDeclarationTest.java`; modify `P/VaultStoragePlugin.java`

**Interfaces:** `public final class LegacyVaultCommand implements BasicCommand` with constructor `LegacyVaultCommand(@NotNull VaultCommand delegate)`

- [x] Write the failing tests.
  `T/PluginDescriptorTest.java`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage;

  import java.io.IOException;
  import java.io.InputStream;
  import java.nio.charset.StandardCharsets;
  import org.junit.jupiter.api.Assertions;
  import org.junit.jupiter.api.Test;

  class PluginDescriptorTest {

      @Test
      void theDescriptorNamesTheMainClassAndHasItsVersionExpanded() throws IOException {
          String descriptor;
          try (InputStream stream = VaultStoragePlugin.class.getResourceAsStream("/paper-plugin.yml")) {
              Assertions.assertNotNull(stream, "paper-plugin.yml is not on the classpath");
              descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          }
          Assertions.assertTrue(descriptor.contains("main: " + VaultStoragePlugin.class.getName()));
          Assertions.assertFalse(descriptor.contains("${"), "the version placeholder was not expanded");
      }
  }
  ```
  `T/permission/PermissionDeclarationTest.java`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage.permission;

  import java.io.IOException;
  import java.io.InputStream;
  import java.nio.charset.StandardCharsets;
  import java.util.Set;
  import java.util.TreeSet;
  import java.util.regex.Matcher;
  import java.util.regex.Pattern;
  import org.junit.jupiter.api.Assertions;
  import org.junit.jupiter.api.Test;

  class PermissionDeclarationTest {

      private static final Pattern DECLARED = Pattern.compile("(?m)^  (vaultstorage\\.[a-z.]+):\\s*$");

      @Test
      void everyNodeInCodeIsDeclaredAndEveryDeclaredNodeIsInCode() throws IOException {
          String descriptor;
          try (InputStream stream = VaultPermission.class.getResourceAsStream("/paper-plugin.yml")) {
              Assertions.assertNotNull(stream, "paper-plugin.yml is not on the classpath");
              descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          }
          Set<String> declared = new TreeSet<>();
          Matcher matcher = DECLARED.matcher(descriptor);
          while (matcher.find()) {
              declared.add(matcher.group(1));
          }
          Set<String> inCode = new TreeSet<>();
          for (VaultPermission permission : VaultPermission.values()) {
              inCode.add(permission.node());
          }
          Assertions.assertEquals(8, inCode.size(), "expected the eight existing nodes");
          Assertions.assertEquals(inCode, declared);
      }
  }
  ```
- [x] Run `./gradlew :plugin:test` and expect FAIL: `paper-plugin.yml is not on the classpath`. Check the report shows 2 tests run.
- [x] Implement `paper-plugin.yml`: the block in the spec's "Descriptor" section, with the `permissions:` map holding the eight nodes from `plugin.yml`, each keeping its `description` and `default`, indented two spaces. Then `git rm plugin/src/main/resources/plugin.yml`.
- [x] Implement `P/command/LegacyVaultCommand.java`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage.command;

  import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
  import io.papermc.paper.command.brigadier.BasicCommand;
  import io.papermc.paper.command.brigadier.CommandSourceStack;
  import java.util.Collection;
  import java.util.List;
  import org.jetbrains.annotations.NotNull;

  /**
   * Bridges the hand-rolled subcommand router onto Paper's command registration, which a
   * paper-plugin.yml plugin must use. Deleted when the commands move to Brigadier.
   */
  @SuppressWarnings("UnstableApiUsage")
  public final class LegacyVaultCommand implements BasicCommand {

      private static final String LABEL = "vault";

      private final @NotNull VaultCommand delegate;

      public LegacyVaultCommand(@NotNull VaultCommand delegate) {
          this.delegate = delegate;
      }

      @Override
      public void execute(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
          this.delegate.onCommand(source.getSender(), null, LABEL, args);
      }

      @Override
      public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
          // Bukkit passes one empty argument when nothing is typed yet; BasicCommand passes none.
          String[] typed = args.length == 0 ? new String[] {""} : args;
          List<String> suggestions = this.delegate.onTabComplete(source.getSender(), null, LABEL, typed);
          return suggestions == null ? List.of() : suggestions;
      }

      @Override
      public @NotNull String permission() {
          return VaultPermission.USER.node();
      }
  }
  ```
  `VaultCommand.onCommand` and `onTabComplete` never read their `Command` parameter, so `null` is passed. Verify that by reading both methods before relying on it.
- [x] In `VaultStoragePlugin.onEnable` replace the `getCommand("vault")` block with:
  ```java
  VaultCommand vaultCommand = new VaultCommand();
  getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
          event.registrar().register("vault", "Vault storage tools", new LegacyVaultCommand(vaultCommand)));
  ```
  (`io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents`), and in `validatePermissionsMapping` replace `getDescription().getPermissions()` with `getPluginMeta().getPermissions()`.
- [x] Run `./gradlew :plugin:test` and expect PASS, 2 tests.
- [x] Run `./gradlew build`.
- [x] Commit: `build: replace plugin.yml with paper-plugin.yml`

## Task 5: Names without DemocracyLib

**Files:** create `P/Integrations.java`, `P/names/NameSource.java`, `P/names/PaperProfileNameSource.java`, `P/names/FallbackNameSource.java`, `P/names/Names.java`, `T/names/PaperProfileNameSourceTest.java`, `T/names/NamesTest.java`; modify `P/VaultStoragePlugin.java`, `P/names/UniqueIdentifierResolver.java`, `P/capture/VaultCaptureService.java`, `P/vault/VaultInventoryService.java`, `P/menu/VaultListMenu.java`, `P/menu/VaultScanMenu.java`, `P/menu/LoadingMenu.java`, `P/command/MenuSubcommand.java`, `P/command/TransferSubcommand.java`, `plugin/build.gradle.kts`, `gradle/libs.versions.toml`, `buildSrc/src/main/kotlin/vaultstorage-conventions.gradle.kts`

**Interfaces:**
```java
public interface NameSource {
    @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player);
    @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name);
}
public final class PaperProfileNameSource implements NameSource {
    public PaperProfileNameSource();
    public PaperProfileNameSource(@NotNull Function<UUID, CompletableFuture<PlayerProfile>> byId,
                                  @NotNull Function<String, CompletableFuture<PlayerProfile>> byName);
}
public final class FallbackNameSource implements NameSource {
    public FallbackNameSource(@NotNull NameSource first, @NotNull NameSource second);
}
public final class Integrations {
    public void primaryNames(@Nullable NameSource source);
    public @Nullable NameSource primaryNames();
    public void bedrockNames(@Nullable NameSource source);
    public @Nullable NameSource bedrockNames();
    public void bedrockClient(@Nullable Predicate<UUID> check);
    public boolean isBedrockClient(@NotNull UUID player);
    public void chestShop(@Nullable ChestShopService service);   // null restores NoOpChestShopService
    public @NotNull ChestShopService chestShop();
}
public final class Names implements NameSource {
    public Names(@NotNull NameSource paper, @NotNull Integrations integrations);
}
```
Every `Integrations` field is `volatile`: name lookups complete on other threads.

- [x] Write the failing tests.
  `T/names/NamesTest.java`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage.names;

  import com.minecraftcitiesnetwork.vaultstorage.Integrations;
  import java.util.Optional;
  import java.util.UUID;
  import java.util.concurrent.CompletableFuture;
  import java.util.concurrent.atomic.AtomicInteger;
  import org.jetbrains.annotations.NotNull;
  import org.junit.jupiter.api.Assertions;
  import org.junit.jupiter.api.Test;

  class NamesTest {

      private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

      /** Answers one fixed name, or empty, and counts how often it was asked. */
      private static final class FixedSource implements NameSource {
          private final Optional<String> name;
          private final AtomicInteger asked = new AtomicInteger();

          private FixedSource(@NotNull Optional<String> name) {
              this.name = name;
          }

          @Override
          public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
              this.asked.incrementAndGet();
              return CompletableFuture.completedFuture(this.name);
          }

          @Override
          public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
              this.asked.incrementAndGet();
              return CompletableFuture.completedFuture(this.name.map(ignored -> PLAYER));
          }
      }

      @Test
      void withNoModulesPaperAnswers() {
          FixedSource paper = new FixedSource(Optional.of("Steve"));
          Names names = new Names(paper, new Integrations());
          Assertions.assertEquals(Optional.of("Steve"), names.nameOf(PLAYER).join());
      }

      @Test
      void aPrimarySourceReplacesPaperAndTheBedrockSource() {
          FixedSource paper = new FixedSource(Optional.of("Steve"));
          FixedSource bedrock = new FixedSource(Optional.of(".Alex"));
          FixedSource namebook = new FixedSource(Optional.empty());
          Integrations integrations = new Integrations();
          integrations.primaryNames(namebook);
          integrations.bedrockNames(bedrock);
          Names names = new Names(paper, integrations);
          Assertions.assertEquals(Optional.empty(), names.nameOf(PLAYER).join());
          Assertions.assertEquals(0, paper.asked.get());
          Assertions.assertEquals(0, bedrock.asked.get());
      }

      @Test
      void theBedrockSourceIsAskedOnlyWhenPaperHasNoAnswer() {
          FixedSource bedrock = new FixedSource(Optional.of(".Alex"));
          Integrations integrations = new Integrations();
          integrations.bedrockNames(bedrock);

          Names found = new Names(new FixedSource(Optional.of("Steve")), integrations);
          Assertions.assertEquals(Optional.of("Steve"), found.nameOf(PLAYER).join());
          Assertions.assertEquals(0, bedrock.asked.get());

          Names missed = new Names(new FixedSource(Optional.empty()), integrations);
          Assertions.assertEquals(Optional.of(".Alex"), missed.nameOf(PLAYER).join());
          Assertions.assertEquals(Optional.of(PLAYER), missed.uuidOf(".Alex").join());
      }

      @Test
      void aModuleThatUnloadsHandsNamesBackToPaper() {
          Integrations integrations = new Integrations();
          integrations.primaryNames(new FixedSource(Optional.of("FromNamebook")));
          Names names = new Names(new FixedSource(Optional.of("Steve")), integrations);
          integrations.primaryNames(null);
          Assertions.assertEquals(Optional.of("Steve"), names.nameOf(PLAYER).join());
      }
  }
  ```
  `T/names/PaperProfileNameSourceTest.java`: three tests, each building the source with lambdas. `aFailedLookupIsEmptyNotAnException` passes `id -> CompletableFuture.failedFuture(new IllegalStateException("down"))` and asserts `Optional.empty()` from `nameOf(...).join()`. `aNameNoAccountCanHaveIsEmpty` passes `name -> { throw new IllegalArgumentException(name); }` as `byName` and asserts `Optional.empty()` from `uuidOf("not a name!").join()`. `aProfileWithNoNameIsEmpty` passes `id -> CompletableFuture.completedFuture(null)` and asserts `Optional.empty()`. No test builds a `PlayerProfile`; the success path is covered on the live server.
- [x] Run `./gradlew :plugin:test --tests "*names*"` and expect FAIL: the classes do not exist.
- [x] Implement the five classes.
  - `PaperProfileNameSource()` delegates to `this(id -> Bukkit.createProfile(id).update(), name -> Bukkit.createProfile(name).update())`. `nameOf` is `byId.apply(player).thenApply(profile -> Optional.ofNullable(profile).map(PlayerProfile::getName)).exceptionally(failure -> Optional.empty())`. `uuidOf` wraps `byName.apply(name)` in `try`/`catch (IllegalArgumentException)` returning a completed empty, then maps `PlayerProfile::getId` the same way.
  - `FallbackNameSource.nameOf` is `first.nameOf(player).thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found) : second.nameOf(player))`; `uuidOf` likewise.
  - `Names.nameOf`: read `integrations.primaryNames()`; if non-null return its answer. Otherwise read `integrations.bedrockNames()`; if null return `paper.nameOf(player)`, else `new FallbackNameSource(paper, bedrock).nameOf(player)`. `uuidOf` likewise.
  - `Integrations`: four `volatile` fields; `chestShop()` returns the field or a shared `NoOpChestShopService`; `isBedrockClient` returns `check != null && check.test(player)`.
- [x] Run `./gradlew :plugin:test --tests "*names*"` and expect PASS, 7 tests.
- [x] Rewire the host.
  - `VaultStoragePlugin`: delete the `democracyLibApi` and `mojangService` fields, their imports, `DemocracyLibApi.instance(...)`, `getMojangService()` and `getBedrockUniqueIdentifierRetriever()`. Add `private final Integrations integrations = new Integrations();`, `private Names names;`, accessors `integrations()` and `names()`, and in `onEnable` `this.names = new Names(new PaperProfileNameSource(), this.integrations);`. Until Task 7, keep Floodgate working from the host: after creating `bedrockUniqueIdentifierRetriever`, call `this.integrations.bedrockNames(...)` with an anonymous `NameSource` that returns `retriever.getPlayerName(uuid).thenApply(Optional::ofNullable).exceptionally(f -> Optional.empty())` and the matching `getUniqueIdentifier` call, and `this.integrations.bedrockClient(FloodgateApi.getInstance()::isFloodgatePlayer)`. Replace `this.chestShopService = chestShop != null ? new ChestShopServiceImp() : new NoOpChestShopService();` with `if (chestShop != null) { this.integrations.chestShop(new ChestShopServiceImp()); }` and make `getChestShopService()` return `this.integrations.chestShop()`.
  - Delete the three `getServicesManager().register(...)` calls for `VaultService`, `WorldGuardService` and `BoltService`.
  - `UniqueIdentifierResolver`: constructor takes one `@NotNull NameSource`. `resolveUuid` keeps the UUID-string shortcut and otherwise returns `names.uuidOf(identifier).thenApply(found -> found.orElse(null))`. `getName` returns `names.nameOf(id).thenApply(found -> found.orElse(null))`. Delete `resolvePlayerName`.
  - The six call-site files: `new UniqueIdentifierResolver(plugin.getMojangService(), plugin.getBedrockUniqueIdentifierRetriever())` becomes `new UniqueIdentifierResolver(plugin.names())`. `mojangService.getName(x).thenAccept(name -> { if (name != null && !name.isBlank()) ... })` becomes `plugin.names().nameOf(x).thenAccept(name -> name.filter(n -> !n.isBlank()).ifPresent(n -> ...))`. In `VaultInventoryService` the title uses `resolved.filter(n -> !n.isBlank()).orElse(ownerUuid.toString())`. Remove every `if (mojangService != null)` guard and its `else` branch.
  - `LoadingMenu.open`: `FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())` becomes `VaultStoragePlugin.getInstance().integrations().isBedrockClient(player.getUniqueId())`; delete the Floodgate import.
- [x] Remove `compileOnly(libs.democracylib)` from `plugin/build.gradle.kts`, the `democracylib` version and library from the catalog, and the jitpack repository block from the conventions.
- [x] Run `grep -rn -i "democracylib\|MojangService" plugin/src buildSrc gradle` and expect no output.
- [x] Run `./gradlew build`.
- [x] Commit: `refactor: look names up through Paper and drop DemocracyLib`

## Task 6: Module system and the Namebook adapter

**Files:** create `buildSrc/src/main/kotlin/vaultstorage-module-conventions.gradle.kts`, `modules/namebook-adapter/build.gradle.kts`, `modules/namebook-adapter/src/main/resources/module-manifest.yml`, `modules/namebook-adapter/src/main/java/com/minecraftcitiesnetwork/vaultstorage/namebook/NamebookModule.java`, `.../namebook/NamebookNameSource.java`, `modules/namebook-adapter/src/test/java/com/minecraftcitiesnetwork/vaultstorage/namebook/NamebookModuleTest.java`; modify `settings.gradle.kts`, `plugin/build.gradle.kts`, `buildSrc/src/main/kotlin/vaultstorage-shared-relocation.gradle.kts`, `P/VaultStoragePlugin.java`

**Interfaces:** `public final class NamebookModule extends SimplePluginModule<VaultStoragePlugin>`; `NamebookNameSource.find(@NotNull ServicesManager) -> @Nullable NameSource`; Gradle configuration `moduleJar` on every module project.

- [x] `buildSrc/src/main/kotlin/vaultstorage-module-conventions.gradle.kts`:
  ```kotlin
  plugins {
      `java-library`
      id("vaultstorage-conventions")
      id("com.gradleup.shadow")
      id("vaultstorage-shared-relocation")
  }

  val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

  dependencies {
      compileOnly(project(":plugin"))
      compileOnly(libs.findLibrary("paper-api").get())
      compileOnly(libs.findLibrary("plugin-infrastructure").get())
      testImplementation(project(":plugin"))
      testRuntimeOnly(libs.findLibrary("paper-api").get())
  }

  tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
      archiveClassifier = ""
  }

  tasks.named<Jar>("jar") {
      archiveClassifier = "unshaded"
  }

  tasks.named("build") {
      dependsOn("shadowJar")
  }

  // The host consumes this to install module jars into the run directory.
  val moduleJar: Configuration by configurations.creating {
      isCanBeConsumed = true
      isCanBeResolved = false
  }

  artifacts {
      add(moduleJar.name, tasks.named<Jar>("shadowJar"))
  }
  ```
- [x] In `vaultstorage-shared-relocation.gradle.kts` replace the empty list with `listOf("com.minecraftcitiesnetwork.pluginInfrastructure")`.
- [x] `settings.gradle.kts`: add `include("modules:namebook-adapter")`.
- [x] `plugin/build.gradle.kts`: add `implementation(libs.plugin.infrastructure)`, and:
  ```kotlin
  val moduleJars: Configuration by configurations.creating {
      isCanBeConsumed = false
      isCanBeResolved = true
  }

  dependencies {
      moduleJars(project(path = ":modules:namebook-adapter", configuration = "moduleJar"))
  }

  val installModules by tasks.registering(Sync::class) {
      from(moduleJars)
      into(layout.projectDirectory.dir("run/plugins/VaultStorage/modules"))
      // Module configs written by a previous run survive.
      preserve { exclude("*.jar") }
  }

  tasks.runServer {
      dependsOn(installModules)
  }
  ```
- [x] Write the failing test, `NamebookModuleTest.java`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage.namebook;

  import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
  import java.io.IOException;
  import java.io.InputStream;
  import java.nio.charset.StandardCharsets;
  import org.junit.jupiter.api.Assertions;
  import org.junit.jupiter.api.Test;

  class NamebookModuleTest {

      private static String resource(String name) throws IOException {
          try (InputStream stream = NamebookModuleTest.class.getClassLoader().getResourceAsStream(name)) {
              Assertions.assertNotNull(stream, name + " is not on the classpath");
              return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
          }
      }

      @Test
      void theManifestNamesTheEntryClassAndTheHost() throws IOException {
          String manifest = resource("module-manifest.yml");
          Assertions.assertTrue(manifest.contains("module-name: namebook-adapter"));
          Assertions.assertTrue(manifest.contains(
                  "entry-class: com.minecraftcitiesnetwork.vaultstorage.namebook.NamebookModule"));
          Assertions.assertTrue(manifest.contains(
                  "expected-plugin-class: " + VaultStoragePlugin.class.getName()));
          Assertions.assertTrue(manifest.contains("reloadable: false"));
      }

      /**
       * The loader resolves the entry class with Class.forName, which fails with a
       * NoClassDefFoundError if it names a Namebook type on a server without Namebook, and that
       * error escapes into the host's onEnable. The class file is scanned as bytes because the
       * entry class cannot be loaded here: its supertype is compileOnly.
       */
      @Test
      void theEntryClassNamesNoNamebookType() throws IOException {
          String classFile = resource(
                  "com/minecraftcitiesnetwork/vaultstorage/namebook/NamebookModule.class");
          Assertions.assertFalse(classFile.contains("com/minecraftcitiesnetwork/namebook/"));
      }
  }
  ```
- [x] Run `./gradlew :modules:namebook-adapter:test` and expect FAIL: `module-manifest.yml is not on the classpath`.
- [x] `modules/namebook-adapter/build.gradle.kts`:
  ```kotlin
  plugins {
      id("vaultstorage-module-conventions")
  }

  dependencies {
      compileOnly(libs.namebook.api)
  }
  ```
- [x] `module-manifest.yml`:
  ```yaml
  module-name: namebook-adapter
  entry-class: com.minecraftcitiesnetwork.vaultstorage.namebook.NamebookModule
  author: MCCitiesNetwork
  expected-plugin-class: com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin
  reloadable: false
  ```
- [x] `NamebookNameSource.java`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage.namebook;

  import com.minecraftcitiesnetwork.namebook.api.Namebook;
  import com.minecraftcitiesnetwork.namebook.api.NamebookFinder;
  import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
  import java.util.Optional;
  import java.util.UUID;
  import java.util.concurrent.CompletableFuture;
  import org.bukkit.plugin.ServicesManager;
  import org.jetbrains.annotations.NotNull;
  import org.jetbrains.annotations.Nullable;

  /** The only class that names Namebook's types. Its futures never complete exceptionally. */
  public final class NamebookNameSource implements NameSource {

      private final @NotNull Namebook namebook;

      private NamebookNameSource(@NotNull Namebook namebook) {
          this.namebook = namebook;
      }

      /** Null when Namebook is installed but has not registered its service. */
      public static @Nullable NameSource find(@NotNull ServicesManager services) {
          return NamebookFinder.find(services).map(NamebookNameSource::new).orElse(null);
      }

      @Override
      public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
          return this.namebook.nameOf(player);
      }

      @Override
      public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
          return this.namebook.uuidOf(name);
      }
  }
  ```
- [x] `NamebookModule.java`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage.namebook;

  import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
  import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
  import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
  import java.nio.file.Path;
  import java.util.logging.Level;
  import org.jetbrains.annotations.NotNull;

  public final class NamebookModule extends SimplePluginModule<VaultStoragePlugin> {

      public NamebookModule() {
      }

      @Override
      public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
          super.initialize(plugin, dataFolder);
          if (!plugin.getServer().getPluginManager().isPluginEnabled("Namebook")) {
              plugin.getLogger().info("Namebook is not installed; namebook-adapter has nothing to do.");
              return;
          }
          try {
              NameSource source = NamebookNameSource.find(plugin.getServer().getServicesManager());
              if (source == null) {
                  plugin.getLogger().warning("Namebook is enabled but has not registered its service.");
                  return;
              }
              plugin.integrations().primaryNames(source);
          } catch (LinkageError error) {
              plugin.getLogger().log(Level.SEVERE,
                      "Namebook is enabled but its classes are not reachable from this module.", error);
          }
      }

      @Override
      public void shutdown(@NotNull VaultStoragePlugin plugin) {
          plugin.integrations().primaryNames(null);
          super.shutdown(plugin);
      }
  }
  ```
- [x] Host: in `VaultStoragePlugin` add a `ModuleLifecycleManager<VaultStoragePlugin> modules` field, and a `startModules()` method called last in `onEnable`:
  ```java
  private void startModules() {
      try {
          Path moduleDir = getDataFolder().toPath().resolve("modules");
          Files.createDirectories(moduleDir);
          this.modules = new ModuleLifecycleManager<>(this, new ModuleLoader(moduleDir));
          this.modules.start();
      } catch (IOException | LinkageError ex) {
          // One bad module jar must not stop the host enabling.
          getLogger().log(Level.SEVERE, "Could not load feature modules.", ex);
      }
  }
  ```
  and as the first statement of `onDisable`: `if (this.modules != null) { this.modules.stop(); }`.
- [x] Run `./gradlew :modules:namebook-adapter:test` and expect PASS, 2 tests.
- [x] Run `./gradlew build`, then check the relocation:
  ```bash
  unzip -l plugin/build/libs/VaultStorage-2.0.0-SNAPSHOT.jar | grep -c "vaultstorage/libraries/com/minecraftcitiesnetwork/pluginInfrastructure/modules/SimplePluginModule.class"   # 1
  unzip -l plugin/build/libs/VaultStorage-2.0.0-SNAPSHOT.jar | grep -c "^.* com/minecraftcitiesnetwork/pluginInfrastructure/"   # 0
  javap -cp modules/namebook-adapter/build/libs/namebook-adapter-2.0.0-SNAPSHOT.jar -v com.minecraftcitiesnetwork.vaultstorage.namebook.NamebookModule | grep -c "vaultstorage/libraries/com/minecraftcitiesnetwork/pluginInfrastructure/modules/SimplePluginModule"   # at least 1
  ```
- [x] Commit: `feat: load feature modules and add the Namebook adapter`

## Task 7: Floodgate adapter

**Files:** move `P/floodgate/BedrockUniqueIdentifierRetriever.java` to `modules/floodgate-adapter/src/main/java/com/minecraftcitiesnetwork/vaultstorage/floodgate/FloodgateNames.java`; create `modules/floodgate-adapter/build.gradle.kts`, `.../module-manifest.yml`, `.../floodgate/FloodgateModule.java`, `.../floodgate/FloodgateModuleTest.java`; modify `settings.gradle.kts`, `plugin/build.gradle.kts`, `P/VaultStoragePlugin.java`

**Interfaces:** `FloodgateNames.create() -> @NotNull NameSource`; `FloodgateNames.clientCheck() -> @NotNull Predicate<UUID>`

- [x] Write the failing test: `FloodgateModuleTest`, the same two tests as `NamebookModuleTest` with `module-name: floodgate-adapter`, entry class `com.minecraftcitiesnetwork.vaultstorage.floodgate.FloodgateModule`, class-file path `com/minecraftcitiesnetwork/vaultstorage/floodgate/FloodgateModule.class`, and forbidden package `org/geysermc/floodgate/`. Write it out in full in the file; it shares no code with the Namebook test.
- [x] Run `./gradlew :modules:floodgate-adapter:test` after adding `include("modules:floodgate-adapter")` and a `build.gradle.kts` holding `plugins { id("vaultstorage-module-conventions") }` and `dependencies { compileOnly(libs.floodgate.api) }`. Expect FAIL: manifest missing.
- [x] `git mv` the retriever to the module as `FloodgateNames.java`, rename the class, and reshape it: a private constructor taking `FloodgateApi`; `public static @NotNull NameSource create()` returning `new FloodgateNames(FloodgateApi.getInstance())`; `implements NameSource` with `nameOf` as the existing XUID conversion and `getGamertagFor` call followed by `.thenApply(Optional::ofNullable).exceptionally(failure -> Optional.empty())`, and `uuidOf` as `getUuidFor(name)` with the same two calls; `public static @NotNull Predicate<UUID> clientCheck()` returning `FloodgateApi.getInstance()::isFloodgatePlayer`.
- [x] `FloodgateModule`: as `NamebookModule`, checking `isPluginEnabled("floodgate")`, and inside the `try` calling `plugin.integrations().bedrockNames(FloodgateNames.create())` and `plugin.integrations().bedrockClient(FloodgateNames.clientCheck())`. `shutdown` sets both back to `null`. Its manifest follows the Namebook one with the two names changed.
- [x] Host: delete the `bedrockUniqueIdentifierRetriever` field, the temporary `integrations.bedrockNames(...)` and `bedrockClient(...)` calls added in Task 5, and the `FloodgateApi` import. Remove `compileOnly(libs.floodgate.api)` from `plugin/build.gradle.kts`. Add the module to `moduleJars`.
- [x] Run `grep -rn "geysermc" plugin/src` and expect no output.
- [x] Run `./gradlew :modules:floodgate-adapter:test` and expect PASS, 2 tests. Run `./gradlew build`.
- [x] Commit: `refactor: move Floodgate into a module and make it optional`

## Task 8: ChestShop adapter

**Files:** move `P/chestshop/adapter/ChestShopServiceImp.java` to `modules/chestshop-adapter/src/main/java/com/minecraftcitiesnetwork/vaultstorage/chestshop/adapter/`; create the module's `build.gradle.kts`, `module-manifest.yml`, `ChestShopModule.java`, `ChestShopModuleTest.java`; modify `settings.gradle.kts`, `plugin/build.gradle.kts`, `P/VaultStoragePlugin.java`

- [x] Write the failing test: `ChestShopModuleTest`, the two tests, with `module-name: chestshop-adapter`, entry class `com.minecraftcitiesnetwork.vaultstorage.chestshop.adapter.ChestShopModule`, and forbidden package `com/Acrobot/`. Written out in full.
- [x] Add `include("modules:chestshop-adapter")` and a `build.gradle.kts` with the module convention and `compileOnly(libs.chestshop)`. Run `./gradlew :modules:chestshop-adapter:test` and expect FAIL.
- [x] `git mv` `ChestShopServiceImp.java` into the module; its `package` line is already right.
- [x] `ChestShopModule`: checks `isPluginEnabled("ChestShop")`; inside `try { } catch (LinkageError)` calls `plugin.integrations().chestShop(new ChestShopServiceImp())`. Because the entry class may not name a `com.Acrobot` type and `ChestShopServiceImp` imports them, the `new` is fine: the entry class names only `ChestShopServiceImp`, and the test proves no `com/Acrobot/` string reaches its class file. `shutdown` calls `plugin.integrations().chestShop(null)`.
- [x] Host: delete the `getPlugin("ChestShop")` block and the `ChestShopServiceImp` import; remove `compileOnly(libs.chestshop)` from `plugin/build.gradle.kts`; add the module to `moduleJars`. Every caller already goes through `getChestShopService()`, which reads the slot at call time.
- [x] Run `grep -rn "Acrobot" plugin/src` and expect no output.
- [x] Run the module's tests and expect PASS, 2 tests. Run `./gradlew build`.
- [x] Commit: `refactor: move the ChestShop integration into a module`

## Task 9: Realty auto-vault module

**Files:** move `P/autovault/*.java` (five files) to `modules/realty-autovault/src/main/java/com/minecraftcitiesnetwork/vaultstorage/autovault/`; create the module's `build.gradle.kts`, `module-manifest.yml`, `AutoVaultModule.java`, `RealtyBinding.java`, `AutoVaultModuleTest.java`; modify `settings.gradle.kts`, `plugin/build.gradle.kts`, `P/VaultStoragePlugin.java`

**Interfaces:** `RealtyBinding.createListener(@NotNull AutoVaultService) -> @NotNull Listener`

- [x] Write the failing test: `AutoVaultModuleTest`, the two tests, with `module-name: realty-autovault`, entry class `com.minecraftcitiesnetwork.vaultstorage.autovault.AutoVaultModule`, and forbidden package `io/github/md5sha256/realty/`. Written out in full.
- [x] Add `include("modules:realty-autovault")` and a `build.gradle.kts` with the module convention plus `compileOnly(libs.realty.paper.api) { isTransitive = false }`, `compileOnly(libs.bolt.bukkit)`, `compileOnly(libs.bolt.common)` and `compileOnly(libs.worldguard)` (the sweep reads Bolt protections and WorldGuard regions through the host's services, whose signatures name those types). Run the test and expect FAIL.
- [x] `git mv` the five files into the module.
- [x] `RealtyBinding`: `public final class` with a private constructor and one static method returning `new RealtyOccupantChangeListener(service)`. It exists so the entry class never names the listener, whose handler signatures name Realty's event types.
- [x] `AutoVaultModule`:
  ```java
  package com.minecraftcitiesnetwork.vaultstorage.autovault;

  import com.minecraftcitiesnetwork.pluginInfrastructure.modules.SimplePluginModule;
  import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
  import com.minecraftcitiesnetwork.vaultstorage.config.ConfigPaths;
  import java.nio.file.Path;
  import java.util.logging.Level;
  import org.jetbrains.annotations.NotNull;
  import org.jetbrains.annotations.Nullable;

  public final class AutoVaultModule extends SimplePluginModule<VaultStoragePlugin> {

      private @Nullable AutoVaultService service;

      public AutoVaultModule() {
      }

      @Override
      public void initialize(@NotNull VaultStoragePlugin plugin, @NotNull Path dataFolder) {
          super.initialize(plugin, dataFolder);
          if (!plugin.getConfig().getBoolean(ConfigPaths.AUTOVAULT_ENABLED.getPath(), true)) {
              plugin.getLogger().info("auto-vault.enabled is false; realty-autovault has nothing to do.");
              return;
          }
          if (!plugin.getServer().getPluginManager().isPluginEnabled("Realty")) {
              plugin.getLogger().info("Realty is not installed; realty-autovault has nothing to do.");
              return;
          }
          try {
              AutoVaultService created = new AutoVaultService(plugin);
              registerListener(RealtyBinding.createListener(created));
              this.service = created;
              plugin.getLogger().info("Auto-vault on region occupant change enabled.");
          } catch (LinkageError error) {
              plugin.getLogger().log(Level.SEVERE,
                      "Realty is enabled but its classes are not reachable from this module.", error);
          }
      }

      @Override
      public void shutdown(@NotNull VaultStoragePlugin plugin) {
          if (this.service != null) {
              this.service.shutdown();
              this.service = null;
          }
          unregisterListeners();
          super.shutdown(plugin);
      }
  }
  ```
- [x] Host: delete the `autoVaultService` field, the "Realty soft dependency" block in `onEnable`, the `autoVaultService.shutdown()` line in `onDisable`, and their imports. Remove `compileOnly(libs.realty.paper.api)` from `plugin/build.gradle.kts`; add the module to `moduleJars`.
- [x] Run `grep -rn "md5sha256" plugin/src` and expect no output.
- [x] Run the module's tests and expect PASS, 2 tests. Run `./gradlew build`.
- [x] Commit: `refactor: move Realty auto-vault into a module`

## Task 10: CI, documentation and the live-server check

**Files:** modify `.github/workflows/build.yml`, `README.md`; create `CLAUDE.md`

- [x] `build.yml`: change the step name to `Set up JDK 25` and `java-version` to `'25'`; add `! -name "*-unshaded.jar"` to the `find` in "Collect JARs".
- [x] `README.md`: replace the build and install sections with: requirements (Paper 26.3, Java 25, MariaDB or MySQL as today; Bolt, WorldGuard, WorldEdit, Essentials required); `./gradlew build`; the host jar `plugin/build/libs/VaultStorage-<version>.jar` goes in `plugins/`; each module jar `modules/<name>/build/libs/<name>-<version>.jar` goes in `plugins/VaultStorage/modules/`; and this table:

  | Module jar | Needs | Gives |
  | --- | --- | --- |
  | `namebook-adapter` | Namebook | Player names from Namebook, Java and Bedrock |
  | `floodgate-adapter` | Floodgate | Bedrock names when Namebook is absent; chat fallback for Bedrock loading screens |
  | `chestshop-adapter` | ChestShop | Shop sign handling during capture |
  | `realty-autovault` | Realty | Automatic vaulting when a region changes occupant |
- [x] `CLAUDE.md`: sections for build commands (`./gradlew build`, `./gradlew test`, `./gradlew :plugin:runServer`), versions (Java 25, Paper 26.3, Gradle 9.8.2), the project list (`:plugin` and the four modules), the host package table from the spec, the rule "a module's entry class names no type from its optional plugin; a `*ModuleTest` enforces it", the rule "host code reads `Integrations` at use time, never at construction", and the programme table from the spec with step 1 marked done.
- [x] Run `./gradlew build`.
- [x] Live server. This cannot be automated. With a Paper 26.3 server holding Bolt, WorldGuard, WorldEdit and Essentials, and a database loaded from `s80037_vaultstorage.sql`, run the seven manual checks in the spec's "Testing" section, in order, and record each result in the commit body. A check that fails stops the task.
- [x] Commit: `docs: describe the module layout and the 26.3 build`
