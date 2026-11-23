import { expect, sleep, test } from '@plugwright/runner';
import { query, seedVaults } from './database.js';
import { Dialogs } from './dialogs.js';
import { pluginFile, withFile } from './files.js';
import { MENU, operators, ordinary } from './players.js';
import { adminVaultActions, dialogTitled, ownVaultActions, vaultFor } from './vaults.js';
import { FLOOR } from './world.js';

// The menus: what a list line says, where Back leads, searching, and paging at its edges.

test('a list line names the block, the world, where it stood and how much it holds',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);

        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        const label = (await dialogTitled(dialogs, 'Browse Vaults')).button('Vault #').label;

        expect(label).toContain(`${owner.username}'s Vault #1`);
        expect(label).toContain('Chest');
        expect(label).toContain(`at world ${x + 2}, ${FLOOR}, ${z}`);
        expect(label).toContain('2 items');
    });

test('a vault holding one stack says "1 item", and an empty one says "empty"',
    async ({ player, server }) => {
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 1);
        const dialogs = new Dialogs(player);
        player.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        expect((await dialogTitled(dialogs, 'Browse Vaults')).button('Vault #').label).toContain('empty');
    });

test('an ordinary player\'s list has no search and shows only their own vaults',
    async ({ player, createPlayer, server }) => {
        const other = await createPlayer();
        seedVaults(other.bot.player.uuid, 3);
        seedVaults(player.bot.player.uuid, 2);
        await ordinary(server, player, [MENU]);
        const dialogs = new Dialogs(player);

        player.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        const list = await dialogTitled(dialogs, 'Browse Vaults');

        expect(list.body).toContain('(2 vaults)');
        expect(list.buttons.some((button) => button.label.includes('Search'))).toBe(false);
        expect(list.buttons.some((button) => button.label.includes(other.username))).toBe(false);
    });

test('an admin finds vaults by the owner\'s UUID and by a vault\'s own id, and nothing by a stranger\'s name',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await operators(server, player);
        const ownerUuid = owner.bot.player.uuid;
        seedVaults(ownerUuid, 2);
        const [vault] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${ownerUuid}' ORDER BY createdTime LIMIT 1`);
        const dialogs = new Dialogs(player);
        player.chat('/vault admin');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');

        (await dialogTitled(dialogs, 'Browse Vaults')).click('Search', { QUERY: ownerUuid });
        const byOwner = await dialogTitled(dialogs, 'Browse Vaults');
        expect(byOwner.body).toContain('(2 vaults)');

        byOwner.click('Search', { QUERY: vault });
        const byVault = await dialogTitled(dialogs, 'Browse Vaults');
        expect(byVault.body).toContain('(1 vaults)');
        expect(byVault.buttons.filter((button) => button.label.includes('Vault #')).length).toBe(1);

        byVault.click('Search', { QUERY: 'nobody_by_this_nam' });
        expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('No vaults found.');

        // An empty search is everything again.
        (await dialogTitled(dialogs, 'Browse Vaults').catch(() => byVault)).click('Search', { QUERY: '   ' });
    });

test('Back from a vault returns to the page of the list it was on',
    async ({ player, server }) => {
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 45);
        const dialogs = new Dialogs(player);
        player.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        (await dialogTitled(dialogs, 'Browse Vaults')).click('Next');
        const second = await dialogTitled(dialogs, 'Browse Vaults');
        expect(second.body).toContain('Page 2 / 3');

        second.click(second.button('Vault #21').label);
        (await dialogTitled(dialogs, 'Vault Action')).click('Back');

        expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('Page 2 / 3');
    });

test('deleting the only vault on the last page shows the page before it',
    async ({ player, server }) => {
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 21);
        const dialogs = new Dialogs(player);
        player.chat('/vault admin');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        (await dialogTitled(dialogs, 'Browse Vaults')).click('Search', { QUERY: player.bot.player.uuid });
        (await dialogTitled(dialogs, 'Browse Vaults')).click('Next');
        const last = await dialogTitled(dialogs, 'Browse Vaults');
        expect(last.body).toContain('Page 2 / 2');

        last.click(last.button('Vault #21').label);
        (await dialogTitled(dialogs, 'Vault Action')).click('Delete Vault');
        (await dialogTitled(dialogs, 'Delete this vault?')).click('Delete');
        await expect(player).toHaveReceivedMessage('Vault deleted.');

        const after = await dialogTitled(dialogs, 'Browse Vaults');
        expect(after.body).toContain('Page 1 / 1');
        expect(after.body).toContain('(20 vaults)');
    });

test('Back from the list returns to the first menu, and Back from an inventory to the vault',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);

        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        (await dialogTitled(dialogs, 'Browse Vaults')).click('Back');
        await dialogTitled(dialogs, 'Vault Capture');

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'view' });
        await sleep(1200);
        owner.bot.closeWindow((owner.bot as any).currentWindow);
        // Closing the inventory brings the vault's menu back.
        await dialogTitled(dialogs, 'Vault Action');
    });

test('a vault\'s menu keeps saying which vault it is, in the delete confirmation too',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const dialogs = new Dialogs(player);

        const actions = await adminVaultActions(player, dialogs, owner.username);
        expect(actions.body).toContain(`at world ${x + 2}, ${FLOOR}, ${z}`);
        actions.click('Delete Vault');
        const confirm = await dialogTitled(dialogs, 'Delete this vault?');
        expect(confirm.body).toContain(`${owner.username}'s Vault #1`);
        expect(confirm.buttons[0].label).toContain('Cancel');
    });

test('the page size in settings.yml takes effect on reload',
    async ({ player, server }) => {
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 12);
        const dialogs = new Dialogs(player);
        try {
            await withFile(pluginFile('settings.yml'), (text) => text.replace(/page-size:\s*\d+/, 'page-size: 5'), async () => {
                player.chat('/vault reload');
                await expect(player).toHaveReceivedMessage('VaultStorage reloaded.');
                player.chat('/vault');
                (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
                const list = await dialogTitled(dialogs, 'Browse Vaults');
                expect(list.body).toContain('Page 1 / 3');
                expect(list.buttons.filter((button) => button.label.includes('Vault #')).length).toBe(5);
            });
        } finally {
            await server.execute('vault reload');
            await sleep(500);
        }
    });

test('a settings.yml that does not parse is refused and the old page size stays',
    async ({ player, server }) => {
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 3);
        const dialogs = new Dialogs(player);
        try {
            await withFile(pluginFile('settings.yml'), () => 'vault-list:\n  page-size: [unterminated\n', async () => {
                player.chat('/vault reload');
                await expect(player).toHaveReceivedMessage('The reload failed');
                player.chat('/vault');
                (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
                expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('Page 1 / 1');
            });
        } finally {
            await server.execute('vault reload');
            await sleep(500);
        }
    });
