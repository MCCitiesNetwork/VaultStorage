import { expect, sleep, test } from '@plugwright/runner';
import { Vec3 } from 'vec3';
import { holdVaultTables } from './database.js';
import { Dialogs } from './dialogs.js';
import { operators } from './players.js';
import { around, defineRegion } from './realty.js';
import { requireChestShop } from './shops.js';
import { ActionBars, captureMode, dialogTitled, lockedChest, lookAtBlock, vaultCount } from './vaults.js';
import { expectBlock, FLOOR, freshSpot, rightClick, serverPosition, sneak, standAt, walkTo } from './world.js';

// A player who gets somewhere on foot, sneaks, and turns to what they click, as a client does.
// Every other spec places its bots with the console and writes its clicks as bare packets.

test('a bot left standing stays where it was put and stays connected',
    async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x, z);

        // Long enough for several of the position packets a standing client repeats.
        await sleep(4000);

        const at = await serverPosition(server, player);
        expect(at).toEqual({ x: x + 0.5, y: FLOOR, z: z + 0.5 });
        await captureMode(player);
    });

test('"Here" finds the region a player walked into, and no region once they walk out',
    async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x, z);
        const name = `walk_${x}`;
        await defineRegion(player, name, around(x, z));
        const dialogs = new Dialogs(player);
        const here = async () => {
            player.chat('/vault admin');
            (await dialogTitled(dialogs, 'Vault Storage')).click('Scan Region');
            (await dialogTitled(dialogs, 'Vault Scan')).click('Here');
        };

        // The region ends five blocks from its middle.
        await walkTo(server, player, x + 9, z);
        await here();
        await expect(player).toHaveReceivedMessage('You are not standing in a region.');

        await walkTo(server, player, x + 3, z);
        await here();
        let results = await dialogs.next();
        for (let attempt = 0; attempt < 5 && !results.body.includes('results'); attempt++) {
            results = await dialogs.next();
        }
        expect(results.body).toContain(`Region ${name}`);
    });

test('capture mode names a chest only once the player has walked within reach of it',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x + 14, z + 1);
        await lockedChest(server, owner, x + 15, z);
        const bars = new ActionBars(player);
        await captureMode(player);

        // Fifteen blocks away: the chest is in view and out of reach.
        await lookAtBlock(player, x + 15, FLOOR, z);
        await expect(bars.next('Vaultable:', 2500)).rejects.toThrow();

        await walkTo(server, player, x + 12, z);
        await lookAtBlock(player, x + 15, FLOOR, z);
        expect(await bars.next('Vaultable:')).toContain(`Owner: ${owner.username}`);
    });

test('a capture clicked the way a client clicks, turning to the block first, vaults it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await captureMode(player);

        const bot = player.bot as any;
        await bot.activateBlock(bot.blockAt(new Vec3(x + 2, FLOOR, z)));

        await expect(player).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'client-click-captured');
    });

test('a player who walks away while their capture is being saved still gets the vault',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await captureMode(player);

        holdVaultTables(4);
        await sleep(500);
        await rightClick(player, x + 2, FLOOR, z);
        await walkTo(server, player, x - 12, z);

        await expect(player).toHaveReceivedMessage('Vaulted.', { timeout: 15000 });
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'walked-away-captured');
        expect(vaultCount(owner)).toBe(1);
    });

test('a sneaking player puts a shop sign on their chest by hand, and the shop is captured by it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requireChestShop();
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await server.execute(`minecraft:item replace entity ${owner.username} weapon.mainhand with minecraft:oak_sign`);
        await sleep(300);

        // Without sneaking this click would open the chest.
        await sneak(owner, true);
        await rightClick(owner, x + 2, FLOOR, z);
        await sneak(owner, false);
        await expectBlock(server, x + 2, FLOOR + 1, z, 'oak_sign', 'sign-placed-by-hand');
        (owner.bot as any)._client.write('update_sign', {
            location: { x: x + 2, y: FLOOR + 1, z }, isFrontText: true,
            text1: owner.username, text2: '1', text3: 'B 5', text4: 'DIAMOND',
        });
        await expect(owner).toHaveReceivedMessage('Shop successfully created');

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR + 1, z);

        await expect(player).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'hand-made-shop-chest-gone');
        await expectBlock(server, x + 2, FLOOR + 1, z, 'air', 'hand-made-shop-sign-gone');
    });
