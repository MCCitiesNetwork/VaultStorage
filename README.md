# VaultStorage

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

Note on legacy commands:
- As of 1.0.1, legacy subcommands (/vault list, /vault open, /vault place, /vault capture) were removed in favor of the unified menu flow via /vault.

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
- vaultstorage.user — basic usage (open menu, list, use common actions)
- vaultstorage.admin — admin override for all plugin actions
- vaultstorage.action.view — allow viewing a Vault’s contents in a virtual inventory
- vaultstorage.action.copy — allow copying items from a Vault to the player inventory
- vaultstorage.action.edit — allow editing Vault contents inside the virtual inventory
- vaultstorage.action.place — allow placing the stored block back into the world
- vaultstorage.admin.override — bypass region/container checks when capturing/placing
- vaultstorage.action.capture — allow entering capture mode and vaulting containers

Notes:
- The capture/placement logic is enforced by both the menu flows and internal policy.
- “Admin override” lets you bypass region and container ownership checks; the UI will clearly indicate when override is active.

### Commands (admin view)
- /vault — opens the main menu (requires vaultstorage.user)


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
- WorldGuard: regions are resolved live from WG’s RegionManager; overlapping regions are supported.
- Bolt: used to read owner of protected blocks and to remove/create protections when vaulting or placing blocks, as appropriate.

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
- List<VaultRegion> getRegionsAt(Block block)
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

### Credits

Developed and maintained by **Alepando**  
for **[DemocracyCraft.net](https://democracycraft.net)**  
as the **Lead Developer** of the Vault plugin.
