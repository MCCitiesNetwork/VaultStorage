import { expect, sleep, test } from '@plugwright/runner';
import { pluginFile, withFile } from './files.js';
import { buy, plotForSale, SWEEP_HAS_RUN, vaultsOf } from './realty.js';
import { clickEntity, entityIdAt, lock, lockedChest, vaultItemCount } from './vaults.js';
import { expectBlock, FLOOR, standAt } from './world.js';

// The sweep when things are not tidy: players offline, chunks unloaded, several owners, the
// module turned off or reworded.

test('a previous occupant who is offline when the region is sold still gets their chest vaulted',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);
        owner.bot.quit();
        await sleep(1000);

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'offline-owners-chest-swept');
        await vaultsOf(owner, 1);
    });

test('a buyer who leaves before the sweep runs does not stop it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);

        await buy(server, buyer, region);
        buyer.bot.quit();

        await vaultsOf(owner, 1);
        // The vault's row exists a tick before the block goes, and nobody is told here.
        await sleep(1500);
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'swept-with-the-buyer-gone');
    });

test('a sweep loads a region nobody is near, and vaults what is in it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);
        // Everyone goes far away, and the chunks are given time to unload.
        for (const bot of [player, owner, buyer]) {
            await standAt(server, bot, x + 4000, z + 4000);
        }
        await sleep(12000);
        await server.execute(`execute unless loaded ${x + 2} ${FLOOR} ${z} run say region-is-unloaded`);
        await expect(server).toHaveReceivedMessage('region-is-unloaded');

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await vaultsOf(owner, 1);
        await server.execute(`forceload add ${x + 2} ${z}`);
        await sleep(1500);
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'unloaded-chest-swept');
        await server.execute(`forceload remove ${x + 2} ${z}`);
    });

test('one sweep vaults the chests of several owners, each to its own owner',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer, second] = await Promise.all([createPlayer(), createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await server.execute(`op ${second.username}`);
        await standAt(server, second, x + 1, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await lockedChest(server, owner, x + 3, z);
        await lockedChest(server, second, x - 2, z);

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await vaultsOf(owner, 2);
        await vaultsOf(second, 1);
        await vaultsOf(buyer, 0);
        for (const chestX of [x + 2, x + 3, x - 2]) {
            await expectBlock(server, chestX, FLOOR, z, 'air', `swept-${chestX}`);
        }
    });

test('a sweep vaults a locked item frame with what it shows',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await server.execute(`summon minecraft:item_frame ${x + 2} ${FLOOR} ${z} {Facing:1b,block_pos:[I;${x + 2},${FLOOR},${z}],Item:{id:"minecraft:diamond",count:1}}`);
        owner.chat('/lock');
        await sleep(500);
        clickEntity(owner, await entityIdAt(owner, 'item_frame', x + 2, z));
        await sleep(700);

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await server.execute(`execute unless entity @e[type=minecraft:item_frame,x=${x + 2},y=${FLOOR},z=${z},distance=..2] run say swept-frame-gone`);
        await expect(server).toHaveReceivedMessage('swept-frame-gone');
        await vaultsOf(owner, 1);
        expect(vaultItemCount(owner)).toBe(2);
    });

test('a sweep unlocks an empty locked chest and leaves it standing',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:chest`);
        await lock(owner, x + 2, z);

        await buy(server, buyer, region);

        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'empty-chest-not-removed');
        await vaultsOf(owner, 0);
        // Unlocked: the buyer, now the region's owner, can put something in it and it is theirs.
        await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.0 with minecraft:diamond 1`);
        await server.execute(`op ${buyer.username}`);
        buyer.chat('/vault capture');
        await expect(buyer).toHaveReceivedMessage('Capture mode is on');
        const { rightClick } = await import('./world.js');
        await rightClick(buyer, x + 2, FLOOR, z);
        await expect(buyer).toHaveReceivedMessage('You may not vault that');
    });

test('a sweep vaults both halves of a previous occupant\'s double chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:chest[facing=north,type=left]`);
        await server.execute(`setblock ${x + 3} ${FLOOR} ${z} minecraft:chest[facing=north,type=right]`);
        await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
        await server.execute(`minecraft:item replace block ${x + 3} ${FLOOR} ${z} container.0 with minecraft:emerald 3`);
        await lock(owner, x + 2, z);

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'swept-left-half');
        await expectBlock(server, x + 3, FLOOR, z, 'air', 'swept-right-half');
        expect(vaultItemCount(owner)).toBe(2);
    });

test('with the module turned off in its config, a sale vaults nothing; turned on again, the next one does',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);
        try {
            await withFile(pluginFile('modules/realty-adapter/config.yml'), (text) => text.replace(/^enabled:.*$/m, 'enabled: false'), async () => {
                await server.execute('vault reload');
                await sleep(1000);
                await buy(server, buyer, region);
                await sleep(SWEEP_HAS_RUN);
                await expectBlock(server, x + 2, FLOOR, z, 'chest', 'module-off-chest-stays');
                await vaultsOf(owner, 0);
            });
        } finally {
            await server.execute('vault reload');
            await sleep(1000);
        }
        // On again: handing the region on now sweeps it.
        player.chat(`/realty transfer ${player.username} ${region}`);
        await vaultsOf(owner, 1);
        // The vault's row exists a tick before the block goes, and nobody is told here.
        await sleep(1500);
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'module-on-chest-swept');
    });

test('the module\'s own messages.yml words what the new occupant is told, and reloads',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);
        try {
            await withFile(pluginFile('modules/realty-adapter/messages.yml'),
                (text) => text.replace(/^occupant-notified:.*$/m, "occupant-notified: '<green>Reworded by the operator.</green>'"), async () => {
                    await server.execute('vault reload');
                    await sleep(1000);
                    await buy(server, buyer, region);
                    await expect(buyer).toHaveReceivedMessage('Reworded by the operator.');
                    await vaultsOf(owner, 1);
                });
        } finally {
            await server.execute('vault reload');
            await sleep(1000);
        }
    });

test('with notify-occupant off, the sweep runs and the new occupant is told nothing',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);
        try {
            await withFile(pluginFile('modules/realty-adapter/config.yml'), (text) => text.replace(/^notify-occupant:.*$/m, 'notify-occupant: false'), async () => {
                await server.execute('vault reload');
                await sleep(1000);
                await buy(server, buyer, region);
                await vaultsOf(owner, 1);
                await sleep(1000);
                await expect(buyer).not.toHaveReceivedMessage('cleared into their vault storage', { timeout: 1500 });
            });
        } finally {
            await server.execute('vault reload');
            await sleep(1000);
        }
    });
