# VaultStorage 

[![CodeFactor](https://www.codefactor.io/repository/github/alepandocr/vault/badge)](https://www.codefactor.io/repository/github/alepandocr/vault)
[![wakatime](https://wakatime.com/badge/user/5ae9efd4-fd6c-4bd2-be4f-597085021e3e/project/d2f0ee34-bb7a-457f-83d4-98455ac8a8b8.svg)](https://wakatime.com/badge/user/5ae9efd4-fd6c-4bd2-be4f-597085021e3e/project/d2f0ee34-bb7a-457f-83d4-98455ac8a8b8)


A Paper plugin that lets players convert container blocks into persistent “Vaults,” store their contents safely, and browse or open them later via intuitive in‑game menus.

This document is organized for three audiences: Players, Server Managers, and Developers.

## For Players (How to use it in‑game)
Vaults are saved versions of container blocks (like chests) that you convert via the in‑game menu. The original block is removed and its items are stored safely; you can open Vaults later from anywhere.

What you can do:
- Open the Vault menu: type /vault in chat.
- Start capture mode from the menu, then right‑click a container block to convert it into a Vault.
  - On‑screen guidance appears. Left‑click to cancel capture at any time.
  - While aiming a container, an action bar shows owner info, whether it’s vaultable, and a human‑readable reason when it isn’t. If you have admin, the action bar shows an ADMIN tag.
- Browse your saved Vaults and open one to view, copy, or edit its contents (depending on your permissions).
- Scan regions: find protected containers in a WorldGuard region and vault them one by one with teleport helpers.

Command (in‑game):
- /vault — opens the main menu (all actions flow through the menu).

## For Server Managers (Installation, Database, Permissions, Menus, Operations)

### Requirements
- Paper 26.3
- Java 25
- WorldEdit, WorldGuard and Bolt installed

### Installation and first run
1) Place `VaultStorage-<version>.jar` in your server's `plugins` folder.
2) Start the server once to generate configuration files and data folders.
3) Stop the server to configure the database.
4) For each optional integration you want, place its module jar in `plugins/VaultStorage/modules/` (see Integrations below). The startup log lists the modules that started.

### Database Setup (MariaDB)
VaultStorage needs **MariaDB 10.11 or later**. MySQL is not supported.

Edit `plugins/VaultStorage/config.yml`:
- `mysql.host`, `mysql.port`, `mysql.database`: where the database is. The database must exist.
- `mysql.user`, `mysql.password`: an account that may create and alter tables in it.
- `mysql.useSSL`: true or false, depending on your database setup.

The keys keep their `mysql.` prefix for now.

The plugin creates and upgrades its tables itself when it starts. If it cannot connect, or an upgrade fails, it logs the reason and disables itself; it does not run without a working database.

#### Upgrading from 1.x
The first start of 2.0 converts the existing tables to a new layout. Every vault and item is kept.
- **Take a database backup first.** The conversion cannot be undone by the plugin; going back to 1.x means restoring that backup.
- It takes about a minute for a database of 80,000 vaults and a million items. The server finishes starting once it is done.
- A vault that has no owner recorded stops the conversion with an error naming the problem. Give it an owner in the `vault_owners` table and start again.

### Permissions
Assign via your permissions plugin (examples: LuckPerms, PermissionsEx):
- vaultstorage.user — basic usage (open menu, list, use common actions)
- vaultstorage.admin — admin override for all plugin actions
- vaultstorage.action.view — allow viewing a Vault’s contents in a virtual inventory
- vaultstorage.action.copy — allow copying items from a Vault to the player inventory
- vaultstorage.action.edit — allow editing Vault contents inside the virtual inventory
- vaultstorage.action.place — allow placing the stored block back into the world
- vaultstorage.admin.override — bypass region/container checks when capturing/placing
- vaultstorage.action.capture — allow entering capture mode and vaulting containers

### Menu and Text Configuration (YAML)
All menu and session texts are generated on first use under:
plugins/VaultStorage/menus/

Most fields accept plain text or MiniMessage formatting (e.g., <gold>…</gold>). Changes take effect the next time a menu is opened.

Key configuration groups:

VaultCaptureSession.yml (capture session texts and action bar)
- captureCancelled — chat message on cancel
- notAllowed — when capture isn’t allowed by policy
- emptyCaptureSkipped — when a non-container or empty container is “unlocked” (no vault)
- capturedOk — after capturing successfully
- noBoltOwner — shown when no Bolt owner exists but override is active; the actor will become the vault owner
- actionBarIdle — action bar while in capture mode but not looking at a valid target
- actionBarContainer — action bar while looking at a block
  - Placeholders: %owner%, %vaultable%, %reasonSegment%, %admin%
- actionBarReasonSegmentTemplate — template that wraps the human‑readable reason
- actionBarReasonAllowedBlank — empty or custom text when allowed
- reasonOwnerSelfInRegion — text for OWNER_SELF_IN_REGION
- reasonContainerOwnerInOverlap — text for CONTAINER_OWNER_IN_OVERLAP
- reasonNotInvolvedNotOwner — text for NOT_INVOLVED_NOT_OWNER
- reasonUnprotectedNoOverride — text for UNPROTECTED_NO_OVERRIDE
- reasonNotInRegion — text for NOT_IN_REGION
- reasonFallback — default text when no specific mapping is used
- actionBarUnprotectedOwner — shown for unprotected containers
- actionBarVaultableYes / actionBarVaultableNo — yes/no labels
- adminModeEnabled — one‑time chat notification when the actor has admin override and starts capture mode
- actionBarAdminModeTag — optional tag appended to the action bar when override is active (injected via %admin%)

Additional placeholders available to reason texts:
- %owner% — resolved owner name (or short UUID) for display
- %regions% — comma‑separated region ids at the target block
- %reasonCode% — the policy reason code (e.g., OWNER_SELF_IN_REGION)

Other menu YAMLs (when present in your build) follow a similar pattern and will include per‑menu placeholders and help texts.

### Integrations
- WorldGuard: regions are resolved live from WG's RegionManager; overlapping regions are supported.
- Bolt: used to read owner of protected blocks and to remove/create protections when vaulting or placing blocks, as appropriate.

Every other integration is a module: a separate jar in `plugins/VaultStorage/modules/`. A module whose plugin is not installed does nothing. An integration whose module jar is missing is off.

| Module jar | Needs | Gives |
| --- | --- | --- |
| `namebook-adapter` | Namebook | Player names from Namebook, Java and Bedrock |
| `floodgate-adapter` | Floodgate | Bedrock names when Namebook is absent; a chat line instead of the loading dialog for Bedrock players |
| `chestshop-adapter` | ChestShop | Shop sign handling during capture |
| `realty-autovault` | Realty | Automatic vaulting when a region changes occupant |
| `griefprevention-adapter` | GriefPrevention | Placing a vault on a claim where the player may build |
| `player-notifications-adapter` | PlayerNotifications | Telling a vault's owner that a container of theirs was vaulted |

Module jars must come from the same build as the plugin jar.

Essentials is no longer used. Before 2.0 the plugin mailed vault owners through it; without the `player-notifications-adapter` module, owners are not told and the server log records each case. PlayerNotifications can itself deliver by Essentials mail.

### Operations and troubleshooting
Health signals in console:
- “Connected to MySQL” on successful DB connect
- Automatic schema creation messages at first run
- Standard enable/disable lifecycle lines from Paper

Common symptoms and checks:
- “No MySQL connection available” — verify mysql.host/port/database/user/password and network reachability; confirm privileges and SSL settings.
- “You don’t have permission.” — grant vaultstorage.user or the specific vaultstorage.action.* permission; admins may use vaultstorage.admin.
- “Services not ready.” — ensure WorldEdit, WorldGuard, and Bolt are present and enabled.
- “Vault not found.” — the provided ID may be invalid or already removed; re-list and try again.

Backups:
- Schedule periodic MySQL backups (full + binlogs if available).
- For quick rollbacks, snapshot the DB before major updates.

## For Developers

VaultStorage has no public API: no other plugin compiles against it, and it registers nothing with the Bukkit Services API.

### Building
```bash
./gradlew build
```
- The plugin jar is `plugin/build/libs/VaultStorage-<version>.jar`.
- Each module jar is `modules/<name>/build/libs/<name>-<version>.jar`.
- `./gradlew :plugin:runServer` starts a Paper 26.3 test server with the module jars installed.

See `CLAUDE.md` for the project layout and conventions.

### Credits

Developed and maintained by **Alepando**  
for **[DemocracyCraft.net](https://democracycraft.net)**  
as the **Lead Developer** of the Vault plugin.
