import { expect, sleep, test, waitUntil, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { query } from './database.js';
import { chestShopInstalled, shop, shopIsActive } from './shops.js';
import { lockedChest } from './vaults.js';
import { expectBlock, FLOOR, freshSpot, standAt } from './world.js';

// The auto-vault sweep: when a region changes hands, what the previous occupant left locked in
// it is vaulted for them. The test server has a stand-in for Realty (e2e-plugins/realty-stub)
// whose console command fires Realty's "region bought" event.

/** Staff, the previous occupant and the buyer at a fresh spot, with a region around it. */
async function soldRegion(server: ServerWrapper, staff: PlayerWrapper, owner: PlayerWrapper, buyer: PlayerWrapper) {
    const { x, z } = freshSpot();
    await Promise.all([
        server.execute(`op ${staff.username}`),
        server.execute(`op ${owner.username}`),
    ]);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    await standAt(server, buyer, x, z - 1);
    const region = `sweep_${x}`;
    staff.chat(`//pos1 ${x - 5},-64,${z - 5}`);
    staff.chat(`//pos2 ${x + 5},-50,${z + 5}`);
    await sleep(300);
    staff.chat(`/rg define ${region}`);
    await expect(staff).toHaveReceivedMessage(region);
    return { x, z, region };
}

/** How many vaults the player owns, once the sweep has had time to store them. */
async function vaultsOf(player: PlayerWrapper, expected: number): Promise<void> {
    const count = () => query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${player.bot.player.uuid}'`)[0];
    await waitUntil(() => count() === String(expected),
        { timeout: 10000, message: `${player.username} has ${count()} vaults, expected ${expected}` });
}

test('when a region is bought, the previous occupant\'s locked chest is vaulted for them',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await soldRegion(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);

        await server.execute(`realtystub bought world ${region} ${buyer.username}`);

        // The buyer is told once the chest has been stored and then removed, in that order; so
        // the block is looked at only after the message.
        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'swept-chest-gone');
        await vaultsOf(owner, 1);
        // Nothing was vaulted to the buyer.
        await vaultsOf(buyer, 0);
    });

test('a sweep leaves the new occupant\'s own locked chest where it is',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await soldRegion(server, player, owner, buyer);
        await server.execute(`op ${buyer.username}`);
        await lockedChest(server, buyer, x + 2, z);

        await server.execute(`realtystub bought world ${region} ${buyer.username}`);

        // The delay is one second; give the sweep time to have run and done nothing.
        await sleep(4000);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'buyers-chest-stays');
        await vaultsOf(buyer, 0);
    });

test('a sweep vaults the previous occupant\'s stocked shop that nobody locked',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        if (!chestShopInstalled()) {
            console.log('ChestShop is not installed on the test server; skipped.');
            return;
        }
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await soldRegion(server, player, owner, buyer);
        await shop(server, owner, x + 2, z, true, false);
        expect(await shopIsActive(x + 2, z)).toBe(true);

        await server.execute(`realtystub bought world ${region} ${buyer.username}`);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'swept-shop-chest-gone');
        await expectBlock(server, x + 2, FLOOR + 1, z, 'air', 'swept-shop-sign-gone');
        await vaultsOf(owner, 1);
        await vaultsOf(buyer, 0);
        expect(await shopIsActive(x + 2, z)).toBe(false);
    });

test('a sweep removes the sign of the previous occupant\'s empty shop and leaves the chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        if (!chestShopInstalled()) {
            console.log('ChestShop is not installed on the test server; skipped.');
            return;
        }
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await soldRegion(server, player, owner, buyer);
        await shop(server, owner, x + 2, z, false, false);
        expect(await shopIsActive(x + 2, z)).toBe(true);

        await server.execute(`realtystub bought world ${region} ${buyer.username}`);

        // Nothing is vaulted, so nobody is told; wait out the one-second delay and the sweep.
        await sleep(4000);
        await expectBlock(server, x + 2, FLOOR + 1, z, 'air', 'swept-empty-shop-sign-gone');
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'swept-empty-shop-chest-stays');
        await vaultsOf(owner, 0);
        expect(await shopIsActive(x + 2, z)).toBe(false);
    });

test('a sweep leaves the new occupant\'s own unlocked shop alone',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        if (!chestShopInstalled()) {
            console.log('ChestShop is not installed on the test server; skipped.');
            return;
        }
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await soldRegion(server, player, owner, buyer);
        await server.execute(`op ${buyer.username}`);
        await shop(server, buyer, x + 2, z, true, false);
        expect(await shopIsActive(x + 2, z)).toBe(true);

        await server.execute(`realtystub bought world ${region} ${buyer.username}`);

        await sleep(4000);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'buyers-shop-chest-stays');
        await vaultsOf(buyer, 0);
        expect(await shopIsActive(x + 2, z)).toBe(true);
    });
