import { expect, sleep, test, waitUntil, type PlayerWrapper } from '@plugwright/runner';
import { query } from './database.js';
import { Dialogs, type Dialog } from './dialogs.js';
import { dialogTitled, lockedChest } from './vaults.js';
import { expectBlock, FLOOR, freshSpot, standAt } from './world.js';

// Scanning a WorldGuard region for locked blocks and vaulting one from the results.

/** Reads dialogs until the scan results arrive. */
async function scanResults(dialogs: Dialogs): Promise<Dialog> {
    for (let attempt = 0; attempt < 6; attempt++) {
        const dialog = await dialogs.next();
        if (dialog.title === 'Vault Scan' && dialog.body.includes('results')) {
            return dialog;
        }
    }
    throw new Error('No scan results were shown');
}

test('a region scan lists a locked chest, vaults it, and drops it from the results',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);

        // A region around the spot, defined the way staff define one.
        const region = `e2e_${x}`;
        player.chat(`//pos1 ${x - 5},-64,${z - 5}`);
        player.chat(`//pos2 ${x + 5},-50,${z + 5}`);
        await sleep(300);
        player.chat(`/rg define ${region}`);
        await expect(player).toHaveReceivedMessage(region);

        const dialogs = new Dialogs(player);
        player.chat('/vault admin');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Scan Region');
        (await dialogTitled(dialogs, 'Vault Scan')).click('Here');

        const results = await scanResults(dialogs);
        expect(results.body).toContain(`Region ${region}`);
        expect(results.body).toContain('(1 results)');
        expect(results.body).toContain(`(${x + 2}, ${FLOOR}, ${z})`);
        results.click('(vault) - 1');

        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'scanned-chest-gone');
        const after = await scanResults(dialogs);
        expect(after.body).toContain('(0 results)');
    });

test('a locked item frame found by a scan is vaulted with what it displays, not just unlocked',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        const frameX = x + 2;
        await server.execute(`summon minecraft:item_frame ${frameX} ${FLOOR} ${z} {Facing:1b,block_pos:[I;${frameX},${FLOOR},${z}],Item:{id:"minecraft:diamond",count:1}}`);

        const frameId = async (bot: PlayerWrapper): Promise<number> => {
            const find = () => Object.values((bot.bot as any).entities as Record<string, any>).find((entity) =>
                entity.name === 'item_frame' && Math.floor(entity.position.x) === frameX && Math.floor(entity.position.z) === z);
            await waitUntil(() => find() !== undefined, { timeout: 5000, message: `${bot.username} never saw the item frame` });
            return find().id;
        };
        owner.chat('/lock');
        await sleep(500);
        (owner.bot as any)._client.write('use_entity', {
            target: await frameId(owner), mouse: 2, x: 0, y: 0, z: 0, sneaking: false, hand: 0,
        });
        await sleep(700);

        const region = `e2e_frame_${x}`;
        player.chat(`//pos1 ${x - 5},-64,${z - 5}`);
        player.chat(`//pos2 ${x + 5},-50,${z + 5}`);
        await sleep(300);
        player.chat(`/rg define ${region}`);
        await expect(player).toHaveReceivedMessage(region);

        const dialogs = new Dialogs(player);
        player.chat('/vault admin');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Scan Region');
        (await dialogTitled(dialogs, 'Vault Scan')).click('Here');
        // The filter button steps through block, non-container, container and entity.
        let results = await scanResults(dialogs);
        for (const filter of ['Filter: Block', 'Filter: Non-Container', 'Filter: Container']) {
            results.click(results.button(filter).label);
            results = await dialogTitled(dialogs, 'Vault Scan');
        }
        expect(results.body).toContain('Item Frame');
        results.click('(vault) - 1');

        await expect(player).toHaveReceivedMessage('Vault captured.');
        await server.execute(`execute unless entity @e[type=minecraft:item_frame,x=${frameX},y=${FLOOR},z=${z},distance=..2] run say scanned-frame-gone`);
        await expect(server).toHaveReceivedMessage('scanned-frame-gone');
        // The frame and the diamond it showed are in the owner's vault.
        expect(query(`SELECT COUNT(*) FROM VaultItem i JOIN Vault v ON v.uuid = i.vaultUuid WHERE v.ownerUuid = '${owner.bot.player.uuid}'`))
            .toEqual(['2']);
    });
