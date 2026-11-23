import { expect, test } from '@plugwright/runner';
import { query, sql, sqlFromInput } from './database.js';
import { Dialogs } from './dialogs.js';
import { operators } from './players.js';
import { dialogTitled } from './vaults.js';

// Thousands of vaults. The menus read a page at a time, so opening one must not get slower
// as the table grows. Runs last: it leaves the table large.

/** Vaults for one owner, inserted a thousand at a time. */
function seedMany(ownerExpression: string, count: number): void {
    const world = '99999999-0000-0000-0000-000000000001';
    sql(`INSERT INTO World (uuid, name) VALUES ('${world}', 'seeded') ON DUPLICATE KEY UPDATE name = name`);
    for (let done = 0; done < count; done += 1000) {
        const rows: string[] = [];
        for (let i = done; i < Math.min(count, done + 1000); i++) {
            rows.push(`(UUID(), ${ownerExpression}, '${world}', ${i}, 64, 0, 'CHEST', NULL, `
                + `'2026-01-01 00:00:00' + INTERVAL ${i} SECOND, '2026-01-01 00:00:00' + INTERVAL ${i} SECOND)`);
        }
        sqlFromInput('INSERT INTO Vault (uuid, ownerUuid, worldUuid, x, y, z, material, blockData, createdTime, updatedTime) VALUES '
            + rows.join(', ') + ';');
    }
}

/** How long the body took, in milliseconds. */
async function timed(body: () => Promise<unknown>): Promise<number> {
    const start = Date.now();
    await body();
    return Date.now() - start;
}

/** Every screen here must be up within this long, however many vaults there are. */
const PROMPT_MS = 3000;

test('an owner with 5,000 vaults opens their list, pages through it and reaches the last page promptly',
    async ({ player, server }) => {
        await operators(server, player);
        seedMany(`'${player.bot.player.uuid}'`, 5000);
        const dialogs = new Dialogs(player);
        player.chat('/vault');
        const menu = await dialogTitled(dialogs, 'Vault Storage');

        let list: any;
        expect(await timed(async () => {
            menu.click('Browse Vaults');
            list = await dialogTitled(dialogs, 'Browse Vaults');
        })).toBeLessThan(PROMPT_MS);
        expect(list.body).toContain('Page 1 / 250 (5000 vaults)');

        expect(await timed(async () => {
            list.click('Next');
            list = await dialogTitled(dialogs, 'Browse Vaults');
        })).toBeLessThan(PROMPT_MS);
        expect(list.body).toContain('Page 2 / 250');
        expect(list.buttons.filter((button: any) => button.label.includes('Vault #')).length).toBe(20);
    });

test('with 30,000 vaults of other owners in the table, an owner\'s list and an admin search stay prompt',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await operators(server, player, owner);
        // 25,000 vaults spread over 25 other owners, and three of this owner's among them.
        for (let other = 0; other < 25; other++) {
            seedMany(`'aaaaaaaa-0000-0000-0000-${String(other).padStart(12, '0')}'`, 1000);
        }
        seedMany(`'${owner.bot.player.uuid}'`, 3);
        expect(Number(query('SELECT COUNT(*) FROM Vault')[0])).toBeGreaterThan(30000);

        const ownerDialogs = new Dialogs(owner);
        owner.chat('/vault');
        const menu = await dialogTitled(ownerDialogs, 'Vault Storage');
        let list: any;
        expect(await timed(async () => {
            menu.click('Browse Vaults');
            list = await dialogTitled(ownerDialogs, 'Browse Vaults');
        })).toBeLessThan(PROMPT_MS);
        expect(list.body).toContain('(3 vaults)');

        const dialogs = new Dialogs(player);
        player.chat('/vault admin');
        const adminMenu = await dialogTitled(dialogs, 'Vault Storage');
        let all: any;
        expect(await timed(async () => {
            adminMenu.click('Browse Vaults');
            all = await dialogTitled(dialogs, 'Browse Vaults');
        })).toBeLessThan(PROMPT_MS);
        expect(await timed(async () => {
            all.click('Search', { QUERY: owner.username });
            all = await dialogTitled(dialogs, 'Browse Vaults');
        })).toBeLessThan(PROMPT_MS);
        expect(all.body).toContain('(3 vaults)');
    });

test('the server keeps ticking on time while lists over a large table are opened',
    { requires: { consoleOutput: 'full' } }, async ({ player, server }) => {
        await operators(server, player);
        const dialogs = new Dialogs(player);
        for (let round = 0; round < 5; round++) {
            player.chat('/vault admin');
            (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
            await dialogTitled(dialogs, 'Browse Vaults');
        }
        // Paper's own measure: milliseconds a tick took, lately. A tick has 50.
        const report = await server.execute('mspt');
        const numbers = (report.replace(/§./g, '').match(/\d+(\.\d+)?/g) ?? []).map(Number);
        expect(numbers.length).toBeGreaterThan(0);
        // The first three figures are the average, minimum and maximum over the last five seconds.
        expect(numbers[2]).toBeLessThan(200);
    });
