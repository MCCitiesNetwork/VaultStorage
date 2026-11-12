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

Edit `plugins/VaultStorage/database.yml`:
- `url`: where the database is, without `jdbc:` in front, for example `mariadb://localhost:3306/vault_storage`. The database must exist. Add options after a question mark, such as `?sslMode=verify-full`.
- `username`, `password`: an account that may create and alter tables in it.

This file is read once, at startup. A reload does not re-read it.

The plugin creates and upgrades its tables itself when it starts. If it cannot connect, or an upgrade fails, it logs the reason and disables itself; it does not run without a working database.

#### Upgrading from 1.x
The first start of 2.0 converts the existing tables to a new layout. Every vault and item is kept.
- **Take a database backup first.** The conversion cannot be undone by the plugin; going back to 1.x means restoring that backup.
- It takes about a minute for a database of 80,000 vaults and a million items. The server finishes starting once it is done.
- A vault that has no owner recorded stops the conversion with an error naming the problem. Give it an owner in the `vault_owners` table and start again.

- `config.yml` is no longer read. The first start writes `database.yml`, `settings.yml` and the auto-vault module's `config.yml` from the values in it. Once those exist you can delete it.
- **Wording you changed in `menus/` and `mail/` is not carried over.** All text is in `messages.yml` now. Move your changes there, then delete the two folders.

### Commands and permissions
Grant these with your permissions plugin (for example LuckPerms).

| Command | Permission | Default |
| --- | --- | --- |
| `/vault`, `/vault menu` | `vaultstorage.command.vault` | op |
| `/vault menu <player>` | `vaultstorage.command.vault.others` | op |
| `/vault capture` | `vaultstorage.action.capture` | op |
| `/vault open <vault id> [view\|copy\|edit]` | `vaultstorage.command.open` and the action's permission | op |
| the same, for someone else's vault | `vaultstorage.command.open.others` | op |
| `/vault admin` | `vaultstorage.command.admin` | op |
| `/vault transfer <vault id> <player>` | `vaultstorage.command.transfer` | op |
| `/vault reload` | `vaultstorage.command.reload` | op |

A player is only shown the subcommands they may use.

| Permission | Allows | Default |
| --- | --- | --- |
| `vaultstorage.action.view` | looking inside a vault | everyone |
| `vaultstorage.action.copy` | taking copies of a vault's items | op |
| `vaultstorage.action.edit` | changing a vault's items | op |
| `vaultstorage.action.place` | placing a vault back into the world | everyone |
| `vaultstorage.action.capture` | vaulting a locked container | op |
| `vaultstorage.bypass.region` | capturing and placing regardless of region and ownership rules | nobody |
| `vaultstorage.scan.teleport` | the teleport button in scan results | op |
| `vaultstorage.admin` | every permission above, and the staff view in menus: everyone's vaults, delete, every region, no scan cooldown | op |

Without `vaultstorage.admin`, a player sees and can scan only the regions they own or are a member of.

#### Permissions that changed in 2.0
| 1.x | 2.0 |
| --- | --- |
| `vaultstorage.user` | `vaultstorage.command.vault`, and `vaultstorage.command.open` for `/vault open` |
| `vaultstorage.admin.override` | `vaultstorage.bypass.region` |
| `vaultstorage.admin` | unchanged; grant a single `vaultstorage.command.*` permission instead if a rank should have only one staff command |

The subcommand aliases `ui`, `cap` and `inv` are gone.

### Configuration files
All are under `plugins/VaultStorage/`.

| File | Holds | Reloads |
| --- | --- | --- |
| `database.yml` | the database address and account | no |
| `settings.yml` | scan batch size, scan cache time, scan cooldown, vaults per page | yes |
| `messages.yml` | every sentence a player sees, as MiniMessage | yes |
| `modules/realty-autovault/config.yml` | auto-vault: on or off, the delay, item frames, telling the occupant | yes |

- `/vault reload` (needs `vaultstorage.command.reload`) re-reads the files marked yes. If `settings.yml` or `messages.yml` does not parse, nothing changes and the log says why.
- The plugin never rewrites these files, so your comments stay. The defaults of the current version are written to `defaults/` on every start; compare with them after an upgrade.
- A key you leave out of `messages.yml` uses the default wording. A key missing from `settings.yml` is named in the startup log.
- In `messages.yml`, a word in angle brackets that is not a colour, such as `<owner>`, is filled in by the plugin. The comment above each line lists the ones it may use.

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
- “You don’t have permission.” — grant `vaultstorage.command.vault` or the specific `vaultstorage.action.*` permission; staff may be given `vaultstorage.admin`.
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
