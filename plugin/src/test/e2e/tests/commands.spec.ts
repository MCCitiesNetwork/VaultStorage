import { expect, sleep, test, waitUntil } from '@plugwright/runner';
import { query, seedVaults } from './database.js';
import { Dialogs } from './dialogs.js';
import { MENU, operators, ordinary } from './players.js';
import { dialogTitled, openWindow } from './vaults.js';

// Every branch of /vault: who may run it, what it answers, and what it does with bad input.

const NO_SUCH_VAULT = '00000000-0000-0000-0000-00000000dead';

/** The id of the one vault seeded for this player. */
function seededVault(ownerUuid: string): string {
    seedVaults(ownerUuid, 1);
    return query(`SELECT uuid FROM Vault WHERE ownerUuid = '${ownerUuid}'`)[0];
}

test('the console is told that /vault and its menus are for players',
    { requires: { consoleOutput: 'full' } }, async ({ server }) => {
        // The console's answer comes back as the command's reply, not through the log.
        for (const line of ['vault', 'vault menu', 'vault admin', 'vault capture']) {
            expect(await server.execute(line)).toContain('Only players can use this.');
        }
    });

test('the console reloads, and transfers a vault by id',
    { requires: { consoleOutput: 'full' } }, async ({ server, player, createPlayer }) => {
        const to = await createPlayer();
        const vault = seededVault(player.bot.player.uuid);

        // Both answer after work off the main thread, so the answer is in the log, not the reply.
        await server.execute('vault reload');
        await expect(server).toHaveReceivedMessage('Reloaded settings.yml, messages.yml');

        await server.execute(`vault transfer ${vault} ${to.username}`);
        await waitUntil(() => query(`SELECT ownerUuid FROM Vault WHERE uuid = '${vault}'`)[0] === to.bot.player.uuid,
            { timeout: 5000, message: 'the console\'s transfer did not change the owner' });
        expect(query(`SELECT ownerUuid FROM Vault WHERE uuid = '${vault}'`)).toEqual([to.bot.player.uuid]);
    });

test('a transfer to a name nobody has is refused and the vault keeps its owner',
    async ({ player, server }) => {
        await operators(server, player);
        const vault = seededVault(player.bot.player.uuid);

        player.chat(`/vault transfer ${vault} nobody_by_this_nam`);
        await expect(player).toHaveReceivedMessage('Could not resolve player: nobody_by_this_nam');
        expect(query(`SELECT ownerUuid FROM Vault WHERE uuid = '${vault}'`)).toEqual([player.bot.player.uuid]);
    });

test('a transfer to the vault\'s own owner changes nothing and says so plainly',
    async ({ player, server }) => {
        await operators(server, player);
        const vault = seededVault(player.bot.player.uuid);

        player.chat(`/vault transfer ${vault} ${player.username}`);
        await expect(player).toHaveReceivedMessage(`transferred from ${player.bot.player.uuid} to ${player.bot.player.uuid}`);
        expect(query(`SELECT ownerUuid FROM Vault WHERE uuid = '${vault}'`)).toEqual([player.bot.player.uuid]);
    });

test('/vault transfer and an id that is not a UUID is refused by the command itself',
    async ({ player, server }) => {
        await operators(server, player);
        player.chat('/vault transfer not-a-uuid Steve');
        await expect(player).toHaveReceivedMessage('Invalid UUID');
        player.chat('/vault open not-a-uuid');
        await sleep(300);
        // Nothing opened and no error reached the log as a stack trace.
        expect((player.bot as any).currentWindow).toBeNull();
    });

test('/vault menu <player> needs its own permission, and then lists that player\'s vaults',
    async ({ player, createPlayer, server }) => {
        const other = await createPlayer();
        seedVaults(other.bot.player.uuid, 2);
        seedVaults(player.bot.player.uuid, 1);

        await ordinary(server, player, [MENU]);
        player.chat(`/vault menu ${other.username}`);
        await expect(player).toHaveReceivedMessage('Incorrect argument for command');

        await ordinary(server, player, [MENU, 'vaultstorage.command.vault.others']);
        const dialogs = new Dialogs(player);
        player.chat(`/vault menu ${other.username}`);
        await expect(player).toHaveReceivedMessage('Resolving player...');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        const list = await dialogTitled(dialogs, 'Browse Vaults');
        expect(list.body).toContain('(2 vaults)');
        expect(list.buttons.filter((button) => button.label.includes(`${other.username}'s Vault #`)).length).toBe(2);
        expect(list.buttons.some((button) => button.label.includes(`${player.username}'s Vault #`))).toBe(false);
    });

test('/vault menu with the sender\'s own name is their own menu, and an unknown name is refused',
    async ({ player, server }) => {
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 1);
        const dialogs = new Dialogs(player);

        player.chat(`/vault menu ${player.username}`);
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('(1 vaults)');

        player.chat('/vault menu nobody_by_this_nam');
        await expect(player).toHaveReceivedMessage('Player not found.');
    });

test('/vault open is refused for someone else\'s vault without the others permission',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const vault = seededVault(owner.bot.player.uuid);

        await ordinary(server, player, [MENU, 'vaultstorage.command.open']);
        player.chat(`/vault open ${vault}`);
        await expect(player).toHaveReceivedMessage('You don\'t have access to that vault.');
        expect((player.bot as any).currentWindow).toBeNull();

        await ordinary(server, player, [MENU, 'vaultstorage.command.open', 'vaultstorage.command.open.others']);
        player.chat(`/vault open ${vault}`);
        await openWindow(player);
    });

test('/vault open refuses an unknown action and an action the player may not use',
    async ({ player, server }) => {
        const vault = seededVault(player.bot.player.uuid);
        await ordinary(server, player, [MENU, 'vaultstorage.command.open']);

        player.chat(`/vault open ${vault} steal`);
        await expect(player).toHaveReceivedMessage('Unknown action. Use view|copy|edit.');

        // Edit and copy are staff actions by default; view is everyone's.
        player.chat(`/vault open ${vault} edit`);
        await expect(player).toHaveReceivedMessage('You don\'t have permission for that action.');
        player.chat(`/vault open ${vault} copy`);
        await sleep(400);
        expect((player.bot as any).currentWindow).toBeNull();

        player.chat(`/vault open ${vault} VIEW`);
        await openWindow(player);
    });

test('/vault open of a vault that does not exist says so, whoever asks',
    async ({ player, server }) => {
        await ordinary(server, player, [MENU, 'vaultstorage.command.open', 'vaultstorage.command.open.others']);
        player.chat(`/vault open ${NO_SUCH_VAULT}`);
        await expect(player).toHaveReceivedMessage('Vault not found.');
    });

test('/vault capture and /vault admin are not offered without their permissions',
    async ({ player, server }) => {
        await ordinary(server, player, [MENU]);
        for (const refused of ['/vault capture', '/vault admin', `/vault transfer ${NO_SUCH_VAULT} Steve`, `/vault open ${NO_SUCH_VAULT}`]) {
            player.chat(refused);
            await expect(player).toHaveReceivedMessage('Incorrect argument for command');
            await sleep(300);
        }
    });

test('the menu\'s Start Capture is refused to a player without the capture permission',
    async ({ player, server }) => {
        await ordinary(server, player, [MENU]);
        const dialogs = new Dialogs(player);
        player.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Start Capture');
        await expect(player).toHaveReceivedMessage('You don\'t have permission to capture blocks.');
    });
