# Menus — design

Date: 2026-11-12
Status: implemented; the decisions below were taken by default and are open to change
Step 6 of 7. Depends on step 5.

## Goal

A player can tell their vaults apart, cannot delete one by a single mis-click, and is never left
looking at a loading dialog after something failed.

## Audit findings this step closes

- Every vault in the list was labelled "<owner>'s Vault #n": nothing said what or where it was.
- Delete was one click, with no confirmation.
- A failed load, scan or open left the loading dialog on screen with nothing behind it.
- "Back" from a vault always returned to page 1 of an unsearched list.
- Entities in scan results were paged over a map whose order changed between pages.
- "yes", "no" and filter names were inserted into sentences, so they could not be translated
  as part of them.

## Decisions taken by default

| Decision | Default taken | Why |
| --- | --- | --- |
| Is the menu framework (`ParentMenu`, `ChildMenu`, `AutoDialog`) replaced? | No | Nothing in the audit is caused by it. The screens that had findings are changed; the framework's static reach goes in step 7. |
| What a vault's label shows | owner, number, block, world and position, item count, date captured | It is what `VaultSummary` already loads in the one list query. |

## Changes

- **Labels.** `menu.VaultLabel` turns a `VaultSummary` into the label's values: `TRAPPED_CHEST`
  becomes "Trapped Chest", the date is `yyyy-MM-dd` in UTC, and the item count picks one of
  `list.items-none`, `list.items-one`, `list.items-many`. The same label is the first line of the
  vault's action menu.
- **Back keeps its place.** The action menu is given the way back by the list that opened it.
- **Delete asks first.** `menu.ConfirmMenu` shows what will be deleted, with Delete and Cancel.
  Cancel returns to the action menu.
- **A loading dialog always ends.** Every path that opened one closes it or replaces it: a
  refused or failed inventory open, a scan that could not start, a failed list load.
- **Entities are sorted** by position, then by id, before they are paged.
- **Whole sentences.** `scan.entry-vaultable` / `scan.entry-not-vaultable`,
  `placement.action-bar-placeable` / `-not-placeable`, `capture.action-bar-vaultable` /
  `-not-vaultable`, and one `scan.filter-btn-*` per filter replace the inserted words.

## Testing

- `VaultLabelTest`: material names, the date in UTC, the three item-count keys.
- `MessageKeysTest` keeps every new key and constant in step, and renders both action bars.
- Live: the list label shows the block and its position; delete needs the confirmation and
  Cancel keeps the vault; the existing 23 specs keep passing.

## Known limitations

- **A world that is not loaded is shown by the first eight characters of its id.**
- **Scan results still name the owner "unknown" and the kind "container" or "block" by
  insertion** (`scan.owner-unknown`, `scan.kind-*`): they are labels, not parts of a sentence.

## As built

- **Back keeps its place through a placement too.** The placement screen's Back returns to the
  vault; a finished placement returns to the page the vault was picked from.
- **A scan that cannot start answers "nothing"** and stops counting as in progress.
