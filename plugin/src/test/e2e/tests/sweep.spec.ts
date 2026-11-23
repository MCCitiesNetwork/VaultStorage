import { expect, sleep, test } from '@plugwright/runner';
import { around, buy, defineRegion, freehold, leasehold, plotForSale, scene, SWEEP_HAS_RUN, vaultsOf } from './realty.js';
import { requireChestShop, shop, shopIsActive } from './shops.js';
import { lockedChest } from './vaults.js';
import { expectBlock, FLOOR, standAt } from './world.js';

// The auto-vault sweep: when a region changes hands, what the previous occupant left locked in
// it is vaulted for them. The region changes hands in Realty itself: a sale, a transfer, a
// rental or a new tenant. Realty replaces the region's WorldGuard owners and members with the
// new occupant before it fires the event the sweep starts from.

test('when a region is bought, the previous occupant\'s locked chest is vaulted for them',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);

        await buy(server, buyer, region);

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
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await server.execute(`op ${buyer.username}`);
        await lockedChest(server, buyer, x + 2, z);

        await buy(server, buyer, region);

        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'buyers-chest-stays');
        await vaultsOf(buyer, 0);
    });

test('a title transferred by staff sweeps the region for the new holder',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, heir] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, heir);
        await lockedChest(server, owner, x + 2, z);

        player.chat(`/realty transfer ${heir.username} ${region}`);

        await expect(heir).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'transferred-chest-gone');
        await vaultsOf(owner, 1);
    });

test('renting a vacant region vaults a locked chest someone left in it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [squatter, tenant] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await scene(server, player, squatter, tenant);
        await defineRegion(player, region, around(x, z));
        await leasehold(player, region);
        await lockedChest(server, squatter, x + 2, z);

        await server.execute(`eco give ${tenant.username} 100`);
        tenant.chat(`/realty rent ${region}`);
        await expect(tenant).toHaveReceivedMessage('You are now leasing region');

        await expect(tenant).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'rented-chest-gone');
        await vaultsOf(squatter, 1);
    });

test('a tenant replaced by staff has their locked chest vaulted for them',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [first, second] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await scene(server, player, first, second);
        await defineRegion(player, region, around(x, z));
        await leasehold(player, region);
        await lockedChest(server, first, x + 2, z);

        // The first tenant moving in sweeps for them, and leaves their own chest.
        player.chat(`/realty set tenant ${first.username} ${region}`);
        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'first-tenants-chest-stays');

        player.chat(`/realty set tenant ${second.username} ${region} --now`);

        await expect(second).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'replaced-tenants-chest-gone');
        await vaultsOf(first, 1);
    });

test('buying the region above another leaves a locked chest in the region below',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await scene(server, player, owner, buyer);
        // Same footprint: the ground floor holds the chest, the flat above it is sold.
        await defineRegion(player, `${region}_ground`, { ...around(x, z), maxY: FLOOR + 1 });
        await defineRegion(player, region, { ...around(x, z), minY: FLOOR + 2 });
        await freehold(player, region, owner);
        await lockedChest(server, owner, x + 2, z);

        await buy(server, buyer, region);

        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'chest-below-stays');
        await vaultsOf(owner, 0);
    });

test('where the bought region overlaps another, that region\'s member keeps their chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer, neighbour] = await Promise.all([createPlayer(), createPlayer(), createPlayer()]);
        const { x, z, region } = await scene(server, player, owner, buyer);
        await server.execute(`op ${neighbour.username}`);
        await standAt(server, neighbour, x + 1, z + 1);
        // The plot is x-5..x+5. The neighbour's region is x+3..x+12, so x+3..x+5 is in both.
        await defineRegion(player, region, around(x, z));
        await defineRegion(player, `${region}_next`, { ...around(x, z), minX: x + 3, maxX: x + 12 });
        player.chat(`/rg addmember ${region}_next ${neighbour.username}`);
        await expect(player).toHaveReceivedMessage(`'${region}_next' updated with new members`);
        await freehold(player, region, owner);
        // In the overlap: the neighbour's. In the plot alone: the previous occupant's.
        await lockedChest(server, neighbour, x + 4, z);
        await lockedChest(server, owner, x - 2, z);

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x - 2, FLOOR, z, 'air', 'occupants-chest-gone');
        await expectBlock(server, x + 4, FLOOR, z, 'chest', 'neighbours-chest-stays');
        await vaultsOf(owner, 1);
        await vaultsOf(neighbour, 0);
    });

test('a sweep vaults the previous occupant\'s stocked shop that nobody locked',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requireChestShop();
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await shop(server, owner, x + 2, z, true, false);
        expect(await shopIsActive(x + 2, z)).toBe(true);

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'swept-shop-chest-gone');
        await expectBlock(server, x + 2, FLOOR + 1, z, 'air', 'swept-shop-sign-gone');
        await vaultsOf(owner, 1);
        await vaultsOf(buyer, 0);
        expect(await shopIsActive(x + 2, z)).toBe(false);
    });

test('a sweep removes the sign of the previous occupant\'s empty shop and leaves the chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requireChestShop();
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await shop(server, owner, x + 2, z, false, false);
        expect(await shopIsActive(x + 2, z)).toBe(true);

        await buy(server, buyer, region);

        // Nothing is vaulted, so nobody is told.
        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, x + 2, FLOOR + 1, z, 'air', 'swept-empty-shop-sign-gone');
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'swept-empty-shop-chest-stays');
        await vaultsOf(owner, 0);
        expect(await shopIsActive(x + 2, z)).toBe(false);
    });

test('a sweep leaves the new occupant\'s own unlocked shop alone',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requireChestShop();
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await server.execute(`op ${buyer.username}`);
        await shop(server, buyer, x + 2, z, true, false);
        expect(await shopIsActive(x + 2, z)).toBe(true);

        await buy(server, buyer, region);

        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'buyers-shop-chest-stays');
        await vaultsOf(buyer, 0);
        expect(await shopIsActive(x + 2, z)).toBe(true);
    });
