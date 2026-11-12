# Commands and permissions — design

Date: 2026-11-12
Status: implemented; the decisions below were taken by default and are open to change
Step 5 of 7. Depends on step 4.

## Goal

`/vault` is one Brigadier tree whose branches a player sees only if they may use them, and every
permission node is declared, named by one scheme and checked without a hidden override.

## Audit findings this step closes

- Exception text was sent to players. No path does that now.
- `vaultstorage.admin` was an override hidden in `VaultPermission.has`: every check silently
  also accepted it. It is now a declared parent whose children are the other nodes.
- Any player with the menu could list every region in the world and scan it.
- The scan results offered a teleport to any player.
- Tab completion ran through a hand-written router that did not know argument types.

## Decisions taken by default

| Decision | Default taken | Why |
| --- | --- | --- |
| Names of nodes that are not commands | `vaultstorage.action.*` keep their names; the region override becomes `vaultstorage.bypass.region`; the scan teleport is `vaultstorage.scan.teleport` | The action nodes already follow one scheme and operators have granted them. Only the node whose name said the wrong thing is renamed. |
| Is there a `vaultstorage.command.capture`? | No. `/vault capture` needs `vaultstorage.action.capture` | The command and the menu button are two doors to one ability. Two nodes would have to be granted together every time. |
| The aliases `ui`, `cap`, `inv` | Dropped | 2.0 is a breaking release, and Brigadier aliases of a sub-literal are duplicate branches. |

## Commands

| Command | Node | Default |
| --- | --- | --- |
| `/vault`, `/vault menu` | `vaultstorage.command.vault` | op |
| `/vault menu <player>` | `vaultstorage.command.vault.others` | op |
| `/vault capture` | `vaultstorage.action.capture` | op |
| `/vault open <vault> [view\|copy\|edit]` | `vaultstorage.command.open`, and the action's node | op |
| the same, for a vault that is not the sender's | `vaultstorage.command.open.others` | op |
| `/vault admin` | `vaultstorage.command.admin` | op |
| `/vault transfer <vault> <player>` | `vaultstorage.command.transfer` | op |
| `/vault reload` | `vaultstorage.command.reload` | op |

`<vault>` is Paper's UUID argument, so a malformed id is refused by the client. `<player>` is a
word, completed from online players, resolved through `NameSource`.

## Other nodes

| Node | Allows | Default | Was |
| --- | --- | --- | --- |
| `vaultstorage.action.view`, `.copy`, `.edit`, `.place`, `.capture` | the action | unchanged | the same |
| `vaultstorage.bypass.region` | capturing and placing regardless of region and ownership rules | false | `vaultstorage.admin.override` |
| `vaultstorage.scan.teleport` | the teleport button in scan results | op | nothing |
| `vaultstorage.admin` | staff view in menus: everyone's vaults, delete, every region, no scan cooldown. Parent of every other node | op | an override inside the code |
| `vaultstorage.user` | removed; see `vaultstorage.command.vault` | | |

A player without `vaultstorage.admin` sees, in the region list, only regions they own or are a
member of, and can scan only those.

## Architecture

- `permission.Permissions`: one `String` constant per node. `PermissionsTest` holds it in step
  with `paper-plugin.yml` in both directions; the runtime check at startup goes.
- `command.VaultCommand`: `create(plugin)` returns the tree. Each branch has `requires`. A
  handler checks the sender, reads permissions on the main thread, works on the database
  executor and replies on the main thread.
- `command.VaultActionArgument`: parses the action word; no Bukkit types.
- `command.TransferOutcome`, `command.OpenOutcome`: sealed; one message per case.
- `VaultPermission`, `Subcommand`, `CommandContext`, the six `*Subcommand` classes and
  `LegacyVaultCommand` are deleted.

`VaultCommand.create` takes the plugin, not each service: the menus it opens still reach the
plugin statically. Step 7 removes that.

## Testing

- `PermissionsTest`: every constant is declared with a description and a default; every declared
  node has a constant; `vaultstorage.admin` lists every other node as a child.
- `VaultActionArgumentTest`: case is folded with the root locale; an unknown word is empty.
- `VaultCommandTreeTest`: the tree has the branches above and no others.
- Live: the existing specs with the new node names; a player who is not an operator and holds
  only `vaultstorage.admin` can run `/vault admin`; a player with only
  `vaultstorage.command.vault` is not offered `/vault reload`.

## Known limitations

- **Operators must re-grant** `vaultstorage.user` as `vaultstorage.command.vault` (and
  `vaultstorage.command.open` if players used `/vault open`) and `vaultstorage.admin.override`
  as `vaultstorage.bypass.region`. The README lists the changes.
