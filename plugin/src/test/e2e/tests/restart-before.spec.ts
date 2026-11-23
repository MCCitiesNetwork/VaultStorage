import { expect, sleep, test } from '@plugwright/runner';
import * as fs from 'node:fs';
import { query, seedVaults, sql } from './database.js';
import { pluginFile, withFile } from './files.js';
import { operators } from './players.js';
import { around, buy, defineRegion, freehold } from './realty.js';
import { NAMES, SPOT, stateFile } from './restart.js';
import { requireRealty } from './shops.js';
import { captureMode, expectVaults, lockedChest } from './vaults.js';
import { FLOOR, rightClick, standAt } from './world.js';

// The first half of the restart tests: state left behind for the server to come back to. Run
// by `./gradlew :plugin:plugwrightRestartTest`, which stops the server after this file and
// starts it again for restart-after.spec.ts. Not part of the ordinary run.

test('restart: before the server stops, a vault is stored, a claim is left behind and a sweep is waiting',
    { requires: { consoleOutput: 'full' } }, async ({ createPlayer, server }) => {
        requireRealty();
        const [staff, owner, buyer, editor] = await Promise.all([
            createPlayer({ username: NAMES.staff }), createPlayer({ username: NAMES.owner }),
            createPlayer({ username: NAMES.buyer }), createPlayer({ username: NAMES.editor }),
        ]);
        const { x, z } = SPOT;
        await operators(server, staff, owner, editor);
        await standAt(server, staff, x, z);
        await standAt(server, owner, x, z + 1);
        await standAt(server, buyer, x, z - 1);
        await standAt(server, editor, x + 1, z - 1);
        // Earlier runs of this test leave their vaults and their region behind.
        for (const bot of [owner, editor]) {
            sql(`DELETE FROM Vault WHERE ownerUuid = '${bot.bot.player.uuid}'`);
        }
        const region = `restart_${Date.now()}`;

        // 1. A vault captured now must still hold its items afterwards.
        await lockedChest(server, editor, x + 2, z + 3);
        await captureMode(staff);
        await rightClick(staff, x + 2, FLOOR, z + 3);
        await expect(staff).toHaveReceivedMessage('Vault captured.');
        await expectVaults(editor, 1);

        // 2. A second vault left claimed, as a crash in the middle of an edit leaves it.
        seedVaults(editor.bot.player.uuid, 1);
        const [claimed] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${editor.bot.player.uuid}' AND material = 'CHEST' ORDER BY createdTime LIMIT 1`);
        sql(`UPDATE Vault SET status = 'CLAIMED' WHERE uuid = '${claimed}'`);

        // 3. A region sold with a sweep that will not have run by the time the server stops.
        await lockedChest(server, owner, x + 2, z);
        await defineRegion(staff, region, around(x, z));
        await freehold(staff, region, owner);
        await withFile(pluginFile('modules/realty-adapter/config.yml'), (text) => text.replace(/^delay-seconds:.*$/m, 'delay-seconds: 30'), async () => {
            await server.execute('vault reload');
            await sleep(1000);
            await buy(server, buyer, region);
            await sleep(1000);
        });
        await server.execute('vault reload');
        expect(query(`SELECT COUNT(*) FROM PendingSweep WHERE regionId = '${region}'`)).toEqual(['1']);
        expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${owner.bot.player.uuid}'`)).toEqual(['0']);

        fs.writeFileSync(stateFile(), JSON.stringify({ region, claimed, x, z }));
    });
