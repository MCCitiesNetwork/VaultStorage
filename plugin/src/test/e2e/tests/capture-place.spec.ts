import { expect, sleep, test } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { dialogTitled, lockedChest } from './vaults.js';
import { startDatabase, stopDatabase } from './database.js';
import { expectBlock, expectItem, FLOOR, freshSpot, GROUND, rightClick, standAt } from './world.js';

// Captures a locked chest into a vault and places it back, the way players do it: commands,
// clicks on blocks, and the dialog menus. Wording is the plugin's default text.

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
        await expect(player).toHaveReceivedMessage('Capture mode is on');
        await rightClick(player, chestX, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, chestX, FLOOR, z, 'air', 'chest-is-gone');

        // Placement: the owner finds the vault in the menu and places it two blocks away.
        const dialogs = new Dialogs(owner);
        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
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
        (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
        const after = await dialogTitled(dialogs, 'Browse Vaults');
        expect(after.body).toContain('No vaults found.');
    });

test('a furnace comes back with its input, fuel and output where they were',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        const furnaceX = x + 2;
        await server.execute(`setblock ${furnaceX} ${FLOOR} ${z} minecraft:furnace`);
        // A diamond does not smelt, so the furnace stays unlit and its contents do not change
        // while it is being captured.
        await server.execute(`minecraft:item replace block ${furnaceX} ${FLOOR} ${z} container.0 with minecraft:diamond 2`);
        await server.execute(`minecraft:item replace block ${furnaceX} ${FLOOR} ${z} container.1 with minecraft:coal 4`);
        await server.execute(`minecraft:item replace block ${furnaceX} ${FLOOR} ${z} container.2 with minecraft:iron_ingot 6`);
        owner.chat('/lock');
        await sleep(500);
        await rightClick(owner, furnaceX, FLOOR, z);
        await sleep(500);

        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture mode is on');
        await rightClick(player, furnaceX, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, furnaceX, FLOOR, z, 'air', 'furnace-is-gone');

        const dialogs = new Dialogs(owner);
        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
        const list = await dialogTitled(dialogs, 'Browse Vaults');
        expect(list.button('Vault #').label).toContain('Furnace');
        list.click(list.button('Vault #').label);
        (await dialogTitled(dialogs, 'Vault Action')).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await rightClick(owner, furnaceX, GROUND, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, furnaceX, FLOOR, z + 2, 'furnace', 'furnace-is-back');
        await expectItem(server, furnaceX, FLOOR, z + 2, 0, 'diamond', 'input-in-its-slot');
        await expectItem(server, furnaceX, FLOOR, z + 2, 1, 'coal', 'fuel-in-its-slot');
        await expectItem(server, furnaceX, FLOOR, z + 2, 2, 'iron_ingot', 'output-in-its-slot');
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
        await expect(player).toHaveReceivedMessage('Capture mode is on');
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
        await expect(player).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, chestX, FLOOR, z, 'air', 'chest-gone-after-recovery');
    });
