import { expect, sleep, test, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { query, queryDatabase } from './database.js';
import { expectBlock, FLOOR, freshSpot, rightClick, standAt } from './world.js';

// Capturing a container that is a ChestShop shop. ChestShop and Treasury come from a
// hibernia-economy checkout (see installE2eEconomy in plugin/build.gradle.kts); without them
// these specs pass without doing anything, and say so.

function chestShopInstalled(): boolean {
    let folder = process.cwd();
    for (let depth = 0; depth < 6; depth++) {
        for (const plugins of [
            path.join(folder, 'generated/local/run/plugins'),
            path.join(folder, 'src/test/e2e/generated/local/run/plugins'),
            path.join(folder, 'plugin/src/test/e2e/generated/local/run/plugins'),
        ]) {
            if (fs.existsSync(plugins)) {
                return fs.readdirSync(plugins).some((name) => /^chestshop-.*\.jar$/.test(name));
            }
        }
        folder = path.dirname(folder);
    }
    return false;
}

/**
 * A chest with the owner's shop sign standing on it, locked by the owner with Bolt or not.
 *
 * The sign is placed on a stone block and the block is turned into the chest afterwards: a
 * click on a chest opens it unless the player sneaks, and these bots send no input packets.
 * The sign text is sent the way a client sends it, so ChestShop registers the shop.
 */
async function shop(server: ServerWrapper, owner: PlayerWrapper, x: number, z: number, withItems: boolean, locked: boolean): Promise<void> {
    await server.execute(`setblock ${x} ${FLOOR} ${z} minecraft:stone`);
    await server.execute(`minecraft:item replace entity ${owner.username} weapon.mainhand with minecraft:oak_sign`);
    await sleep(300);
    await rightClick(owner, x, FLOOR, z);
    await sleep(400);
    await server.execute(`setblock ${x} ${FLOOR} ${z} minecraft:chest`);
    if (withItems) {
        await server.execute(`minecraft:item replace block ${x} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
    }
    (owner.bot as any)._client.write('update_sign', {
        location: { x, y: FLOOR + 1, z }, isFrontText: true,
        text1: owner.username, text2: '1', text3: 'B 5', text4: 'DIAMOND',
    });
    await expect(owner).toHaveReceivedMessage('Shop successfully created');
    if (!locked) {
        return;
    }
    owner.chat('/lock');
    await sleep(500);
    await rightClick(owner, x, FLOOR, z);
    await sleep(500);
}

/** Whether ChestShop's own registry still lists a shop at the sign as open for trade. */
async function shopIsActive(x: number, z: number): Promise<boolean> {
    // The registry is written off the main thread, a moment after the sign goes.
    await sleep(1000);
    const rows = queryDatabase('treasury',
        `SELECT active FROM chestshop_shop WHERE sign_x = ${x} AND sign_y = ${FLOOR + 1} AND sign_z = ${z}`);
    expect(rows).toHaveLength(1);
    return rows[0] === '1';
}

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
        if (!chestShopInstalled()) {
            console.log('ChestShop is not installed on the test server; skipped.');
            return;
        }
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
        if (!chestShopInstalled()) {
            console.log('ChestShop is not installed on the test server; skipped.');
            return;
        }
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
        if (!chestShopInstalled()) {
            console.log('ChestShop is not installed on the test server; skipped.');
            return;
        }
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
