import { expect, sleep, test } from '@plugwright/runner';
import { startDatabase, stopDatabase } from './database.js';
import { Dialogs } from './dialogs.js';
import { dialogTitled, openWindow, ownVaultActions, vaultCount, vaultFor, vaultItemCount } from './vaults.js';
import { expectBlock, FLOOR, GROUND, rightClick } from './world.js';

// The database going away in the middle of things. Nothing may be lost, nothing may exist
// twice, and nobody may be left behind a loading screen.

test('a placement while the database is down places nothing and keeps the vault',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);

        stopDatabase();
        try {
            await rightClick(owner, x + 2, GROUND, z + 2);
            await expect(owner).toHaveReceivedMessage('could not be placed. Nothing was lost.', { timeout: 25000 });
            await expectBlock(server, x + 2, FLOOR, z + 2, 'air', 'nothing-placed-during-outage');
        } finally {
            startDatabase();
        }

        expect(vaultCount(owner)).toBe(1);
        expect(vaultItemCount(owner)).toBe(2);
        // And it can be placed once the database is back.
        (await dialogTitled(dialogs, 'Vault Action')).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);
        await rightClick(owner, x + 2, GROUND, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
    });

test('the vault list while the database is down says it could not be loaded, and works again afterwards',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        owner.chat('/vault');
        const menu = await dialogTitled(dialogs, 'Vault Capture');

        stopDatabase();
        try {
            menu.click('Browse Vaults');
            await expect(owner).toHaveReceivedMessage('The vault list could not be loaded.', { timeout: 25000 });
        } finally {
            startDatabase();
        }

        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('(1 vaults)');
    });

test('opening a vault while the database is down says it could not be opened',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        const actions = await ownVaultActions(owner, dialogs);

        stopDatabase();
        try {
            actions.click('Open Inventory', { ACTION: 'view' });
            await expect(owner).toHaveReceivedMessage('The vault could not be opened.', { timeout: 25000 });
            expect((owner.bot as any).currentWindow).toBeNull();
        } finally {
            startDatabase();
        }
    });

test('an edit closed while the database is down is reported, and the vault can be edited again afterwards',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);

        stopDatabase();
        try {
            owner.bot.closeWindow(window);
            await expect(owner).toHaveReceivedMessage('The vault could not be saved. Tell staff.', { timeout: 25000 });
            await expect(server).toHaveReceivedMessage('could not be saved after', { timeout: 5000 });
        } finally {
            startDatabase();
        }

        // Nothing was changed, so the vault holds what it held. And it is not left claimed:
        // the owner can open it for editing again.
        expect(vaultItemCount(owner)).toBe(2);
        // The release is retried every few seconds until the database answers.
        await sleep(8000);
        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        await openWindow(owner);
    });

test('an admin delete while the database is down deletes nothing and says it failed',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(player);
        const { adminVaultActions } = await import('./vaults.js');
        (await adminVaultActions(player, dialogs, owner.username)).click('Delete Vault');
        const confirm = await dialogTitled(dialogs, 'Delete this vault?');

        stopDatabase();
        try {
            confirm.click('Delete');
            await expect(player).toHaveReceivedMessage('Vault deletion failed.', { timeout: 25000 });
        } finally {
            startDatabase();
        }

        expect(vaultCount(owner)).toBe(1);
    });
