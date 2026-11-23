import { expect, sleep, test, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { query } from './database.js';
import { requireChestShop, shop, shopIsActive } from './shops.js';
import { expectBlock, FLOOR, freshSpot, rightClick, standAt } from './world.js';

// Capturing a container that is a ChestShop shop. ChestShop and Treasury are installed by
// installE2eEconomy in plugin/build.gradle.kts; without them these specs fail.

async function scene(server: ServerWrapper, staff: PlayerWrapper, owner: PlayerWrapper) {
    const { x, z } = freshSpot();
    await Promise.all([
        server.execute(`op ${staff.username}`),
        server.execute(`op ${owner.username}`),
    ]);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    return { x, z, shopX: x + 2 };
}

test('capturing a shop by its sign vaults the container and removes the sign',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requireChestShop();
        const owner = await createPlayer();
        const { z, shopX } = await scene(server, player, owner);
        await shop(server, owner, shopX, z, true, true);
        expect(await shopIsActive(shopX, z)).toBe(true);

        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture session started');
        // The click is on the sign; the capture goes to the container under it.
        await rightClick(player, shopX, FLOOR + 1, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, shopX, FLOOR, z, 'air', 'shop-chest-gone');
        await expectBlock(server, shopX, FLOOR + 1, z, 'air', 'shop-sign-gone');
        // ChestShop was told: a shop left in its registry would still be offered to buyers.
        expect(await shopIsActive(shopX, z)).toBe(false);
    });

test('an empty shop container keeps its block and loses its lock and its sign',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requireChestShop();
        const owner = await createPlayer();
        const { z, shopX } = await scene(server, player, owner);
        await shop(server, owner, shopX, z, false, true);

        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture session started');
        await rightClick(player, shopX, FLOOR, z);
        await expect(player).toHaveReceivedMessage('ChestShop sign removed.');
        await expect(player).toHaveReceivedMessage('Protection removed.');
        await expectBlock(server, shopX, FLOOR, z, 'chest', 'empty-shop-chest-stays');
        await expectBlock(server, shopX, FLOOR + 1, z, 'air', 'empty-shop-sign-gone');
        expect(await shopIsActive(shopX, z)).toBe(false);
    });

test('a shop container nobody locked is vaulted to the player the shop belongs to',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requireChestShop();
        const owner = await createPlayer();
        const { z, shopX } = await scene(server, player, owner);
        await shop(server, owner, shopX, z, true, false);
        // The registry row is written off the main thread.
        expect(await shopIsActive(shopX, z)).toBe(true);

        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture session started');
        await rightClick(player, shopX, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, shopX, FLOOR, z, 'air', 'unlocked-shop-chest-gone');
        // The owner comes from Treasury's shop registry: no lock says whose the chest is, and
        // the vault must not go to whoever clicked.
        expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${owner.bot.player.uuid}'`)).toEqual(['1']);
        expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${player.bot.player.uuid}'`)).toEqual(['0']);
        expect(await shopIsActive(shopX, z)).toBe(false);
    });
