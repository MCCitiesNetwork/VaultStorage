import { expect, sleep, test } from '@plugwright/runner';
import { query } from './database.js';
import { Dialogs } from './dialogs.js';
import { MENU, ordinary } from './players.js';
import { dialogTitled, ownVaultActions, vaultFor } from './vaults.js';

// What a client could send that the menus never offer. A dialog's answer comes from the
// client, so a modified client can put anything in it.

test('an ordinary player\'s action menu offers view only, and no delete',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        await ordinary(server, owner, [MENU]);
        const dialogs = new Dialogs(owner);

        const actions = await ownVaultActions(owner, dialogs);

        expect(actions.buttons.some((button) => button.label.includes('Delete'))).toBe(false);
        actions.button('Open Inventory');
        actions.button('Place Block');
    });

test('a forged "edit" answer from a player who may only view does not let them take items',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        await ordinary(server, owner, [MENU]);
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        await sleep(1500);
        const window = (owner.bot as any).currentWindow;
        if (window) {
            await owner.bot.clickWindow(0, 0, 1).catch(() => undefined);
            await sleep(300);
            owner.bot.closeWindow(window);
            await sleep(500);
        }

        await server.execute(`execute unless items entity ${owner.username} container.* minecraft:diamond run say forged-edit-took-nothing`);
        await expect(server).toHaveReceivedMessage('forged-edit-took-nothing');
        const ownerUuid = owner.bot.player.uuid;
        expect(query(`SELECT COUNT(*) FROM VaultItem i JOIN Vault v ON v.uuid = i.vaultUuid WHERE v.ownerUuid = '${ownerUuid}'`))
            .toEqual(['2']);
    });

test('a forged "copy" answer from a player who may only view gives them no copies',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        await ordinary(server, owner, [MENU]);
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'copy' });
        await sleep(1500);
        const window = (owner.bot as any).currentWindow;
        if (window) {
            await owner.bot.clickWindow(0, 0, 0).catch(() => undefined);
            await sleep(300);
            owner.bot.closeWindow(window);
            await sleep(300);
        }

        await server.execute(`execute unless items entity ${owner.username} container.* minecraft:diamond run say forged-copy-gave-nothing`);
        await expect(server).toHaveReceivedMessage('forged-copy-gave-nothing');
    });

test('a forged answer that is no action at all opens the vault to view, not to edit',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);

        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'steal' });
        await sleep(1500);
        const window = (owner.bot as any).currentWindow;
        expect(window).not.toBeNull();
        await owner.bot.clickWindow(0, 0, 1).catch(() => undefined);
        await sleep(300);
        owner.bot.closeWindow(window);

        await server.execute(`execute unless items entity ${owner.username} container.* minecraft:diamond run say nonsense-action-took-nothing`);
        await expect(server).toHaveReceivedMessage('nonsense-action-took-nothing');
    });

test('a player who loses the place permission after opening the menu cannot place',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        await ordinary(server, owner, [MENU]);
        const dialogs = new Dialogs(owner);
        const actions = await ownVaultActions(owner, dialogs);

        await server.execute(`lp user ${owner.username} permission set vaultstorage.action.place false`);
        await sleep(700);
        actions.click('Place Block');
        const placement = await dialogTitled(dialogs, 'Vault Placement').catch(() => undefined);
        placement?.click('Start Placement');
        await sleep(500);
        const { rightClick } = await import('./world.js');
        const spot = owner.bot.entity.position;
        await rightClick(owner, Math.floor(spot.x) + 1, -61, Math.floor(spot.z) + 3);

        await expect(owner).toHaveReceivedMessage('You don\'t have permission to place vaults.');
        const ownerUuid = owner.bot.player.uuid;
        expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${ownerUuid}'`)).toEqual(['1']);
    });
