import { expect, sleep, test } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { adminVaultActions, openWindow, ownVaultActions, vaultFor } from './vaults.js';

// Opening a vault as an inventory: viewing changes nothing, editing saves, and only one player
// can edit a vault at a time.

test('viewing a vault shows its items in their slots and lets nothing be taken',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'view' });
        const window = await openWindow(owner);
        expect(window.slots[0]?.name).toBe('diamond');
        expect(window.slots[13]?.name).toBe('stone');

        // A click on the diamonds is cancelled: they stay in the vault and out of the player's hands.
        await owner.bot.clickWindow(0, 0, 0).catch(() => undefined);
        await sleep(300);
        owner.bot.closeWindow(window);
        await server.execute(`execute unless items entity ${owner.username} container.* minecraft:diamond run say took-nothing`);
        await expect(server).toHaveReceivedMessage('took-nothing');

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'view' });
        expect((await openWindow(owner)).slots[0]?.name).toBe('diamond');
    });

test('editing a vault saves what was taken out',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);
        // Shift-click moves the diamonds into the player's own inventory.
        await owner.bot.clickWindow(0, 0, 1);
        await sleep(300);
        owner.bot.closeWindow(window);
        await server.execute(`execute if items entity ${owner.username} container.* minecraft:diamond run say has-diamonds`);
        await expect(server).toHaveReceivedMessage('has-diamonds');

        // Closing saved it: the vault no longer holds the diamonds, and still holds the stone.
        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'view' });
        const after = await openWindow(owner);
        expect(after.slots[0]).toBeNull();
        expect(after.slots[13]?.name).toBe('stone');
    });

test('a vault being edited cannot be opened for editing by someone else',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const ownerDialogs = new Dialogs(owner);
        const staffDialogs = new Dialogs(player);

        (await ownVaultActions(owner, ownerDialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);

        (await adminVaultActions(player, staffDialogs, owner.username)).click('Open Inventory', { ACTION: 'edit' });
        await expect(player).toHaveReceivedMessage('Someone else is using that vault.');

        // Once the owner closes it, the vault is free again.
        owner.bot.closeWindow(window);
        await sleep(500);
        (await adminVaultActions(player, staffDialogs, owner.username)).click('Open Inventory', { ACTION: 'edit' });
        await openWindow(player);
    });
