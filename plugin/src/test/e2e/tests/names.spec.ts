import { expect, sleep, test } from '@plugwright/runner';
import * as fs from 'node:fs';
import { query, seedVaults } from './database.js';
import { Dialogs } from './dialogs.js';
import { pluginsFolder } from './files.js';
import { operators } from './players.js';
import { requireFloodgate, requireNamebook } from './shops.js';
import { dialogTitled } from './vaults.js';

// Names, with Namebook and Floodgate on the server. Each module says at start-up what it took
// over. A bot cannot join as a Bedrock player, so only what Floodgate's module does for Java
// players is covered.

function serverLog(): string {
    return fs.readFileSync(`${pluginsFolder()}/../logs/latest.log`, 'utf8');
}

test('with Namebook installed, its module takes over name lookups and says so',
    async () => {
        requireNamebook();
        const log = serverLog();
        expect(log).toContain('Player names are read from Namebook.');
        expect(log).not.toContain('Namebook is not installed');
        expect(log).not.toContain('Namebook is enabled but');
    });

test('with Floodgate installed, its module supplies the Bedrock check and says so',
    async () => {
        requireFloodgate();
        const log = serverLog();
        expect(log).toContain('Bedrock names and the Bedrock client check come from Floodgate');
        expect(log).not.toContain('Floodgate is not installed');
        expect(log).not.toContain('Floodgate is enabled but');
    });

test('a Java player is not taken for a Bedrock one: they get the dialog menus',
    async ({ player, server }) => {
        requireFloodgate();
        await operators(server, player);
        const dialogs = new Dialogs(player);
        player.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
        // The loading screen is a dialog too; a Bedrock player would get a chat line in its place.
        await dialogTitled(dialogs, 'Browse Vaults');
        await expect(player).not.toHaveReceivedMessage('Loading, one moment...', { timeout: 1000 });
    });

test('a player who has left is still found by name: the list shows it and an admin search finds their vaults',
    async ({ player, createPlayer, server }) => {
        requireNamebook();
        const owner = await createPlayer();
        await operators(server, player);
        const name = owner.username;
        seedVaults(owner.bot.player.uuid, 2);
        owner.bot.quit();
        await sleep(1500);
        const dialogs = new Dialogs(player);

        player.chat('/vault admin');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
        (await dialogTitled(dialogs, 'Browse Vaults')).click('Search', { QUERY: name });
        const found = await dialogTitled(dialogs, 'Browse Vaults');

        expect(found.body).toContain('(2 vaults)');
        expect(found.buttons.filter((button) => button.label.includes(`${name}'s Vault #`)).length).toBe(2);
    });

test('a name is matched whatever its case',
    async ({ player, createPlayer, server }) => {
        requireNamebook();
        const owner = await createPlayer();
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 1);
        const [vault] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${player.bot.player.uuid}'`);

        player.chat(`/vault transfer ${vault} ${owner.username.toUpperCase()}`);

        await expect(player).toHaveReceivedMessage(`to ${owner.bot.player.uuid}`);
    });
