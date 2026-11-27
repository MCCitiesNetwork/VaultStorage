import { expect, sleep, test } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { adminVaultActions, dialogTitled, openWindow, ownVaultActions, vaultCount, vaultFor, vaultItemCount } from './vaults.js';

// A vault opened as an inventory: copying, putting items in, and what happens when the editor
// leaves or the vault is deleted while it is open.

test('copying gives the player a copy and leaves the vault as it was',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'copy' });
        const window = await openWindow(owner);
        await owner.bot.clickWindow(0, 0, 0).catch(() => undefined);
        await sleep(400);
        owner.bot.closeWindow(window);
        await sleep(400);

        await server.execute(`execute if items entity ${owner.username} container.* minecraft:diamond run say got-a-copy`);
        await expect(server).toHaveReceivedMessage('got-a-copy');
        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'view' });
        const after = await openWindow(owner);
        expect(after.slots[0]?.name).toBe('diamond');
        expect(after.slots[0]?.count).toBe(3);
        expect(after.slots[13]?.name).toBe('stone');
    });

test('an item put into a vault while editing is saved in the slot it was put in',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        await server.execute(`minecraft:item replace entity ${owner.username} hotbar.0 with minecraft:emerald 11`);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);
        const hotbarStart = window.slots.length - 9;
        // Pick the emeralds up and put them down in slot 5 of the vault.
        await owner.bot.clickWindow(hotbarStart, 0, 0);
        await owner.bot.clickWindow(5, 0, 0);
        await sleep(300);
        owner.bot.closeWindow(window);
        await sleep(600);

        expect(vaultItemCount(owner)).toBe(3);
        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'view' });
        const after = await openWindow(owner);
        expect(after.slots[5]?.name).toBe('emerald');
        expect(after.slots[5]?.count).toBe(11);
        await server.execute(`execute unless items entity ${owner.username} container.* minecraft:emerald run say emeralds-left-the-player`);
        await expect(server).toHaveReceivedMessage('emeralds-left-the-player');
    });

test('a vault emptied by its owner stays in the list as an empty vault',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);
        await owner.bot.clickWindow(0, 0, 1);
        await owner.bot.clickWindow(13, 0, 1);
        await sleep(300);
        owner.bot.closeWindow(window);
        await sleep(600);

        expect(vaultCount(owner)).toBe(1);
        expect(vaultItemCount(owner)).toBe(0);
        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
        const list = await dialogTitled(dialogs, 'Browse Vaults');
        expect(list.button('Vault #').label).toContain('empty');
    });

test('an editor who disconnects with the vault open has their change saved and the vault freed',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const ownerName = owner.username;
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        await openWindow(owner);
        await owner.bot.clickWindow(0, 0, 1);
        await sleep(300);
        owner.bot.quit();
        await sleep(1500);

        // The diamonds went with the player; the vault holds the stone and can be edited again.
        expect(vaultItemCount(owner)).toBe(1);
        const staffDialogs = new Dialogs(player);
        (await adminVaultActions(player, staffDialogs, ownerName)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(player);
        expect(window.slots[0]).toBeNull();
        expect(window.slots[13]?.name).toBe('stone');
    });

test('a vault that is open for editing is not deleted from under its editor',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const ownerDialogs = new Dialogs(owner);
        const staffDialogs = new Dialogs(player);
        await server.execute(`minecraft:item replace entity ${owner.username} hotbar.0 with minecraft:emerald 11`);
        (await ownVaultActions(owner, ownerDialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);
        // The owner puts emeralds in. If the vault vanished now, they would vanish with it.
        await owner.bot.clickWindow(window.slots.length - 9, 0, 1);
        await sleep(300);

        (await adminVaultActions(player, staffDialogs, owner.username)).click('Delete Vault');
        (await dialogTitled(staffDialogs, 'Delete this vault?')).click('Delete');
        await expect(player).toHaveReceivedMessage('Someone else has that vault open');
        expect(vaultCount(owner)).toBe(1);

        owner.bot.closeWindow(window);
        await sleep(600);
        expect(vaultItemCount(owner)).toBe(3);
    });

test('a vault transferred while its old owner is viewing it belongs to the new owner afterwards',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, heir] = await Promise.all([createPlayer(), createPlayer()]);
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'view' });
        const window = await openWindow(owner);
        const { query } = await import('./database.js');
        const [vault] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${owner.bot.player.uuid}'`);

        player.chat(`/vault transfer ${vault} ${heir.username}`);
        await expect(player).toHaveReceivedMessage('transferred from');
        owner.bot.closeWindow(window);
        await sleep(400);

        expect(vaultCount(owner)).toBe(0);
        expect(vaultCount(heir)).toBe(1);
        expect(vaultItemCount(heir)).toBe(2);
    });
