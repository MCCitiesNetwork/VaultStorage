import { expect, sleep, test } from '@plugwright/runner';
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
