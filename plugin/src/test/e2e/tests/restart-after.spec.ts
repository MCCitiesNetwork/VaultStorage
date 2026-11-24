import { expect, sleep, test } from '@plugwright/runner';
import * as fs from 'node:fs';
import { query } from './database.js';
import { Dialogs } from './dialogs.js';
import { operators } from './players.js';
import { logFile, NAMES, stateFile } from './restart.js';
import { dialogTitled, openWindow, vaultCount, vaultItemCount } from './vaults.js';
import { expectBlock, FLOOR, standAt } from './world.js';

// The second half of the restart tests: the server has been stopped and started since
// restart-before.spec.ts ran. See that file.

function state(): { region: string; claimed: string; x: number; z: number } {
    return JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
}

test('restart: a sweep that was waiting when the server stopped runs after it starts',
    { requires: { consoleOutput: 'full' } }, async ({ createPlayer, server }) => {
        const { x, z } = state();
        expect(fs.readFileSync(logFile(), 'utf8')).toContain('auto-vault sweep(s) from before the restart');
        const [staff, owner] = await Promise.all([
            createPlayer({ username: NAMES.staff }), createPlayer({ username: NAMES.owner }),
        ]);
        await operators(server, staff);
        await standAt(server, staff, x, z);

        // Due thirty seconds after the sale; the restart took longer, or nearly as long.
        for (let waited = 0; waited < 60 && query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${owner.bot.player.uuid}'`)[0] === '0'; waited++) {
            await sleep(1000);
        }
        expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${owner.bot.player.uuid}'`)).toEqual(['1']);
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'sweep-resumed-after-restart');
        expect(query(`SELECT COUNT(*) FROM PendingSweep WHERE regionId = '${state().region}'`)).toEqual(['0']);
    });

test('restart: a vault left claimed when the server stopped is free again, and the log says so',
    { requires: { consoleOutput: 'full' } }, async ({ createPlayer, server }) => {
        const { claimed } = state();
        const editor = await createPlayer({ username: NAMES.editor });
        await operators(server, editor);
        expect(query(`SELECT claimToken IS NULL FROM Vault WHERE uuid = '${claimed}'`)).toEqual(['1']);
        const log = fs.readFileSync(logFile(), 'utf8');
        expect(log).toContain(`Vault ${claimed} was still claimed from before the last shutdown`);

        editor.chat(`/vault open ${claimed} edit`);
        await openWindow(editor);
    });

test('restart: a capture that was being saved when the server stopped ended in one place',
    { requires: { consoleOutput: 'full' } }, async ({ createPlayer, server }) => {
        const { x, z } = state();
        const staff = await createPlayer({ username: NAMES.staff });
        await operators(server, staff);
        await standAt(server, staff, x, z);
        const owner = staff.bot.player.uuid;

        // Stored, and so removed from the world: not left standing beside its own vault.
        expect(vaultCount(staff)).toBe(1);
        expect(vaultItemCount(staff)).toBe(2);
        await expectBlock(server, x + 3, FLOOR, z + 3, 'air', 'capture-finished-at-shutdown');
        // The capture let go of its vault itself; startup found nothing of it to release.
        const [vault] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${owner}'`);
        expect(fs.readFileSync(logFile(), 'utf8')).not.toContain(`Vault ${vault} was still claimed`);
    });

test('restart: a vault stored before the server stopped still holds its items in their slots',
    { requires: { consoleOutput: 'full' } }, async ({ createPlayer, server }) => {
        const editor = await createPlayer({ username: NAMES.editor });
        await operators(server, editor);
        expect(vaultItemCount(editor)).toBe(2);
        const dialogs = new Dialogs(editor);

        editor.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
        const list = await dialogTitled(dialogs, 'Browse Vaults');
        list.click(list.buttons.find((button) => button.label.includes('2 items'))!.label);
        (await dialogTitled(dialogs, 'Vault Action')).click('Open Inventory', { ACTION: 'view' });
        const window = await openWindow(editor);
        expect(window.slots[0]?.name).toBe('diamond');
        expect(window.slots[13]?.name).toBe('stone');
    });
