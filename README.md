# VaultStorage

A Paper plugin that lets players convert container blocks into persistent “Vaults,” store their contents safely, and browse or open them later via intuitive in‑game menus.

This document is organized for three audiences: Players, Server Managers, and Developers.

## For Players (How to use it in‑game)
Vaults are saved versions of container blocks (like chests) that you convert via a menu. The original block is removed and its items are stored safely in the database; you can open these Vaults later from anywhere.

What you can do:
- Open the Vault menu: type /vault in chat.
- Start capture mode from the menu, then right‑click any container block to convert it into a Vault.
  - On‑screen guidance will appear. Left‑click to cancel capture at any time.
  - While aiming a container, an action bar shows owner info and whether it’s vaultable.
- Browse your saved Vaults and open one to view, copy, or edit its contents (depending on your permissions).
- Region scan: find protected containers in a region (via the Scan menu) and vault them one by one, with teleport helpers.

Command reference (in‑game):
- /vault — opens the main menu.
- /vault menu — same as /vault.
- /vault list [mine|all] — shows your Vaults; admins may browse by world.
- /vault open <vaultId> [view|copy|edit] — opens a specific Vault with a chosen action.

If a command says you don’t have permission, ask a server admin to enable it for you.

## For Server Managers (Installation, Database, Permissions, Menus, Operations)

### Requirements
- Paper 1.21.8 (or compatible)
- Java 21
- WorldEdit and WorldGuard installed
- Bolt installed (for ownership/protection integration)

### Installation and first run
1) Place the plugin JAR in your server’s plugins folder (along with WorldEdit, WorldGuard, and Bolt).
2) Start the server once to generate configuration files and data folders.
3) Stop the server to configure the database.

### Database Setup (MySQL)
Edit the plugin config at: plugins/VaultStorage/config.yml

mysql keys you can set:
- host: your MySQL host (e.g., 127.0.0.1)
- port: 3306 (or your custom port)
- database: vault_storage (or your chosen schema name)
- user: a database user with create/select/insert/update/delete privileges
- password: the user’s password
- useSSL: true/false depending on your DB setup

Behavior on startup:
- The plugin ensures the database exists (creates it if missing).
- It connects and creates the necessary tables automatically.

Best practices:
- Use a dedicated DB user with minimum required privileges.
- Keep regular backups of the database.
- Monitor connectivity and latency; the plugin runs DB work off the main thread to keep gameplay smooth.

### Permissions
Assign via your permissions plugin (examples: LuckPerms, PermissionsEx):
- vault.user — basic usage (open menu, list, use common actions)
- vault.admin — admin override for elevated actions
- vault.action.view — allow viewing a Vault’s contents in a virtual inventory
- vault.action.copy — allow copying items from a Vault to the player inventory
- vault.action.edit — allow editing Vault contents inside the virtual inventory
- vault.action.place — allow placing the stored block back into the world

Defaults are conservative (typically op); grant to user groups as appropriate.

### Commands (admin view)
- /vault — opens the main menu (requires vault.user)
- /vault menu — explicit alias for the main menu (requires vault.user)
- /vault list [mine|all] — list player vaults; with vault.admin, can browse per world
- /vault open <vaultId> [view|copy|edit] — opens a Vault with the requested mode
  - Access check: owners and admins
- /vault place <vaultId> — attempts to place the original block back
  - Requires vault.action.place

Tab completion:
- Subcommands complete for players with permission.
- For /vault open, action suggestions filter by the player’s permissions.

### Menu Configuration (YAML)
Menu texts and labels are generated on first use and live under:
plugins/VaultStorage/menus/

Most fields accept plain text or MiniMessage formatting (e.g., <gold>…</gold>). Changes take effect the next time a menu is opened.

VaultCaptureMenu.yml
- title — dialog title (supports %player%)
- instruction — how to start capture (supports %player%)
- cancelHint — how to cancel (supports %player%)
- startBtn — button to start capture
- browseBtn — button to browse vaults
- closeBtn — button to close
- captureCancelled — chat message on cancel (supports %player%)
- notAContainer — when the target isn’t a container
- capturedOk — after capturing successfully
- openScanBtn — opens the scan menu
- actionBarIdle — action bar while in capture mode
- actionBarContainer — action bar when looking at a container; placeholders: %owner%, %vaultable%
- actionBarUnprotectedOwner — text used when no Bolt protection is found
- actionBarVaultableYes — shown when the container is vaultable
- actionBarVaultableNo — shown when it isn’t

VaultListMenu.yml
- title — dialog title (supports %query%)
- searchLabel — label above the search text box (supports %query%)
- searchBtn — search button label (supports %query%)
- backBtn — back button label
- noneFound — message for no results (supports %query%)
- loading — message while loading
- itemFormat — format per result; placeholders: %owner%, %index%

VaultActionMenu.yml
- title — dialog title
- actionLabel — label for the action selector
- openBtn — button to open inventory
- placeBtn — button to place the block
- backBtn — back button label
- notFound — message when a vault doesn’t exist
- noActions — message when no actions are available for the player
- actionOptionFormat — label format per action; placeholder: %action%
- inventoryTitle — title for the virtual inventory; placeholders: %id%, %action%
- loading — loading message
- saved — message after saving edits
- placing — message when placing begins
- placeOk — placement success prefix; placeholder: %msg%
- placeFail — placement failure prefix; placeholder: %msg%

VaultScanMenu.yml
- title — dialog title (supports %player%)
- noneFound — shown when no protected containers are found in a region
- servicesMissing — shown when required services aren’t available
- notAContainer — when the block isn’t a container
- vaultedOk — after vaulting a scanned entry
- resultsHeader — header for results view; placeholders: %region%, %count%
- entryLine — per-entry line; placeholders: %x%, %y%, %z%, %owner%
- entryVaultButton — label for the vault action
- entryTeleportButton — label for teleport
- searchLabel — label for region search
- searchBtn — search button label
- hereBtn — resolve regions at the player’s current position
- regionNotFound — when a typed region doesn’t exist; placeholder: %region%

VaultRegionListMenu.yml
- title — dialog title; placeholders: %player%, %query%
- header — above the list; placeholders: %count%, %page%, %pages%, %query%
- regionBtn — per-region button label; placeholder: %region%
- noneFound — when no regions match
- prevPageBtn, nextPageBtn — pagination buttons
- backBtn — back to scan menu
- servicesMissing — when integration services aren’t ready
- regionNotFound — placeholder: %region%

Editing tips:
- Use MiniMessage sparingly for readability.
- Keep lines under ~120 characters for clean displays.
- Reopen the menu to see changes instantly.

### Integrations
- WorldGuard: regions are resolved live from WG’s RegionManager; no internal caching is required.
- Bolt: used to read owner of protected blocks and to remove/create protections when vaulting or placing blocks, as appropriate.

### Operations and troubleshooting
Health signals in console:
- “Connected to MySQL” on successful DB connect
- Automatic schema creation messages at first run
- Standard enable/disable lifecycle lines from Paper

Common symptoms and checks:
- “No MySQL connection available” — verify mysql.host/port/database/user/password and network reachability; confirm privileges and SSL settings.
- “You don’t have permission.” — grant vault.user or the specific vault.action.* permission; admins may use vault.admin.
- “Services not ready.” — ensure WorldEdit, WorldGuard, and Bolt are present and enabled.
- “Vault not found.” — the provided ID may be invalid or already removed; re-list and try again.

Backups:
- Schedule periodic MySQL backups (full + binlogs if available).
- For quick rollbacks, snapshot the DB before major updates.

Updates:
- Replace the JAR during maintenance windows.
- Keep a DB backup and test on a staging server when possible.

Uninstall:
- Remove the JAR from plugins and restart. Database contents remain intact for archival or later reuse.

## For Developers (API and Examples)
VaultStorage registers services with the Bukkit Services API and performs database work off the main thread where applicable. Acquire services and program against the interfaces below.

### Getting services
```java
var services = getServer().getServicesManager();
var vaultSvcReg = services.getRegistration(net.democracycraft.vault.api.service.VaultService.class);
var wgSvcReg = services.getRegistration(net.democracycraft.vault.api.service.WorldGuardService.class);
var boltSvcReg = services.getRegistration(net.democracycraft.vault.api.service.BoltService.class);
```
Always null‑check providers before use.

### VaultService (persistence)
High‑level operations:
- VaultEntity createVault(UUID worldUuid, int x, int y, int z, UUID ownerUuid, String material, String blockData)
- Vault createVault(UUID worldUuid, int x, int y, int z, UUID ownerUuid, String material, String blockData, List<ItemStack> contents)
- Optional<VaultEntity> get(UUID vaultUuid)
- VaultEntity findByLocation(UUID worldUuid, int x, int y, int z)
- void delete(UUID vaultUuid)
- void setOwner(UUID vaultUuid, UUID ownerUuid)
- UUID getOwner(UUID vaultUuid)
- List<VaultEntity> listByOwner(UUID ownerUuid)
- List<VaultEntity> listInWorld(UUID worldUuid)
- void putItem(UUID vaultUuid, int slot, int amount, byte[] itemBytes)
- void removeItem(UUID vaultUuid, int slot)
- List<VaultItemEntity> listItems(UUID vaultUuid)

Example: create a vault and get its items 
```java
Vault vault = vaultService.createVault(world.getUID(), x, y, z, ownerUuid, materialName, blockData, capturedItems);
List<ItemStack> items = vault.contents();
```

### WorldGuardService (regions)
Use to query regions for permissions or to guide UI:
- List<VaultRegion> getRegionsAt(BoundingBox box, World world)
- List<VaultRegion> getRegionsIn(World world)

### BoltService (protections)
Owner and protection utilities:
- UUID getOwner(Block block)
- boolean isOwner(UUID playerUUID, Block block)
- List<Block> getProtectedBlocks(UUID playerUUID, BoundingBox box, World world)
- List<Block> getProtectedBlocksIn(BoundingBox box, World world)
- void removeProtection(Block block)
- void createProtection(Block block, UUID ownerUuid)

### Data model overview
At a glance:
- Vault: identified by a UUID; bound to (worldUuid, x, y, z); has ownerUuid and metadata about the original block (material, blockData); createdAt timestamp is stored.
- Vault items: each record stores slot index, amount, and a serialized item payload.
- World dictionary: worlds are synchronized on enable to ensure referential integrity.

Concurrency and threading:
- Heavy or I/O operations run asynchronously.
- UI interactions (menus, inventories) marshal back to the main thread when needed.

### Extending and integrating
- Add your own UI flows by implementing dialogs and calling VaultService.
- Build region‑aware rules using WorldGuardService (e.g., owners/memberships with VaultRegion.isOwner/isMember).
- Compose with Bolt protections when transforming blocks.