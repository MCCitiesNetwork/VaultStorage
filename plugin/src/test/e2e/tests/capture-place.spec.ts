import { expect, sleep, test, type PlayerWrapper } from '@plugwright/runner';
import { Dialogs, type Dialog } from './dialogs.js';
import { startDatabase, stopDatabase } from './database.js';
import { expectBlock, expectItem, FLOOR, freshSpot, GROUND, rightClick, standAt } from './world.js';

// Captures a locked chest into a vault and places it back, the way players do it: commands,
// clicks on blocks, and the dialog menus. Wording is the plugin's default text.

/** Reads dialogs until one has this title; the loading dialog comes in between. */
async function dialogTitled(dialogs: Dialogs, title: string): Promise<Dialog> {
    for (let attempt = 0; attempt < 5; attempt++) {
        const dialog = await dialogs.next();
        if (dialog.title === title) {
            return dialog;
        }
    }
    throw new Error(`No dialog titled "${title}" was shown`);
}

/** A chest at the spot with a diamond stack in slot 0 and stone in slot 13, locked by its owner. */
async function lockedChest(server: any, owner: PlayerWrapper, x: number, z: number): Promise<void> {
    await server.execute(`setblock ${x} ${FLOOR} ${z} minecraft:chest`);
    await server.execute(`minecraft:item replace block ${x} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
    await server.execute(`minecraft:item replace block ${x} ${FLOOR} ${z} container.13 with minecraft:stone 5`);
    // Bolt answers on the action bar, which a bot's chat log does not hold, so nothing is asserted
    // here. A chest that is not locked cannot be captured, so the capture below proves the lock.
    owner.chat('/lock');
    await sleep(500);
    await rightClick(owner, x, FLOOR, z);
    await sleep(500);
}

test('a locked chest is captured into a vault and placed back with its items in their slots',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        const chestX = x + 2;
        await lockedChest(server, owner, chestX, z);

        // Capture: the operator vaults the owner's chest.
        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture session started');
        await rightClick(player, chestX, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, chestX, FLOOR, z, 'air', 'chest-is-gone');

        // Placement: the owner finds the vault in the menu and places it two blocks away.
        const dialogs = new Dialogs(owner);
        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        const list = await dialogTitled(dialogs, 'Browse Vaults');
        list.click(list.button('Vault #').label);
        (await dialogTitled(dialogs, 'Vault Action')).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');

        const placeX = x + 2;
        const placeZ = z + 2;
        // Clicking the top of the ground block places the vault on it.
        await rightClick(owner, placeX, GROUND, placeZ);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, placeX, FLOOR, placeZ, 'chest', 'chest-is-back');
        await expectItem(server, placeX, FLOOR, placeZ, 0, 'diamond', 'diamonds-in-slot-0');
        await expectItem(server, placeX, FLOOR, placeZ, 13, 'stone', 'stone-in-slot-13');

        // The vault is gone once placed: the list is empty.
        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        const after = await dialogTitled(dialogs, 'Browse Vaults');
        expect(after.body).toContain('No vaults found.');
    });

test('a capture while the database is down leaves the chest and its items in place',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        const chestX = x + 2;
        await lockedChest(server, owner, chestX, z);

        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture session started');
        stopDatabase();
        try {
            await rightClick(player, chestX, FLOOR, z);
            await expect(player).toHaveReceivedMessage('could not be saved', { timeout: 20000 });
            await expectBlock(server, chestX, FLOOR, z, 'chest', 'chest-still-there');
            await expectItem(server, chestX, FLOOR, z, 0, 'diamond', 'diamonds-still-there');
            await expectItem(server, chestX, FLOOR, z, 13, 'stone', 'stone-still-there');
        } finally {
            startDatabase();
        }

        // The session is still usable, and with the database back the same click now works.
        await rightClick(player, chestX, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, chestX, FLOOR, z, 'air', 'chest-gone-after-recovery');
    });
