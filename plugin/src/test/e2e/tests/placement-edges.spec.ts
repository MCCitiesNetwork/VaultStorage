import { expect, sleep, test } from '@plugwright/runner';
import { query, seedVaults, sql } from './database.js';
import { Dialogs } from './dialogs.js';
import { operators } from './players.js';
import {
    adminVaultActions, captureMode, dialogTitled, expectVaults, lock, lockedChest, openWindow, ownVaultActions,
    placeFirstVault, vaultCount, vaultFor,
} from './vaults.js';
import { expectBlock, expectBlockState, expectItem, FLOOR, freshSpot, GROUND, leftClick, rightClick, rightClickFace, standAt } from './world.js';

// Placing a vault back: where it may go, what it becomes, and what is left afterwards.

test('a target that is not empty refuses the placement, keeps the vault and returns to it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z + 2} minecraft:stone`);
        const dialogs = new Dialogs(owner);

        await placeFirstVault(owner, dialogs, x + 2, z + 2);

        await expect(owner).toHaveReceivedMessage('already a block there');
        await expectBlock(server, x + 2, FLOOR, z + 2, 'stone', 'stone-not-replaced');
        expect(vaultCount(owner)).toBe(1);
        // Back at the vault, to try somewhere else.
        (await dialogTitled(dialogs, 'Vault Action')).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);
        await rightClick(owner, x + 3, GROUND, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        await expectItem(server, x + 3, FLOOR, z + 2, 0, 'diamond', 'second-try-has-its-items');
    });

test('a left-click ends placement mode and the vault stays in storage',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);

        await leftClick(owner, x, GROUND, z + 3);
        await expect(owner).toHaveReceivedMessage('Placement cancelled.');

        await rightClick(owner, x + 2, GROUND, z + 2);
        await sleep(1200);
        await expectBlock(server, x + 2, FLOOR, z + 2, 'air', 'nothing-placed-after-cancel');
        expect(vaultCount(owner)).toBe(1);
    });

test('a chest comes back facing the way it faced, and does not join a chest beside it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:chest[facing=east]`);
        await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
        await lock(owner, x + 2, z);
        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vaulted.');

        // A neighbour facing the same way, which a chest placed by hand would join.
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z + 3} minecraft:chest[facing=east]`);
        await placeFirstVault(owner, new Dialogs(owner), x + 2, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');

        await expectBlockState(server, x + 2, FLOOR, z + 2, 'chest[facing=east,type=single]', 'placed-chest-single-facing-east');
        await expectBlockState(server, x + 2, FLOOR, z + 3, 'chest[type=single]', 'neighbour-still-single');
    });

test('a vault is placed against the face that was clicked',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z + 2} minecraft:stone`);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);

        // Face 5 is east: the vault goes one block east of the stone.
        await rightClickFace(owner, x + 2, FLOOR, z + 2, 5);

        await expect(owner).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x + 3, FLOOR, z + 2, 'chest', 'placed-east-of-the-stone');
    });

test('a placed vault is locked for its owner, whoever placed it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const staffDialogs = new Dialogs(player);

        // Staff place the owner's vault from the admin menu.
        (await adminVaultActions(player, staffDialogs, owner.username)).click('Place Block');
        (await dialogTitled(staffDialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);
        await rightClick(player, x + 2, GROUND, z + 2);
        await expect(player).toHaveReceivedMessage('Vault placed.');
        await expectVaults(owner, 0);

        // Vaulting it again gives it back to the owner: the lock on the placed block is theirs.
        await captureMode(player);
        // Staff were told "Capture mode is on" once already, while setting up, so that wait
        // returns at once. A click can reach the server before the command that precedes it.
        await sleep(500);
        await rightClick(player, x + 2, FLOOR, z + 2);
        await expectVaults(owner, 1);
        expect(vaultCount(player)).toBe(0);
    });

test('after a placement the owner is taken back to the list while vaults remain, and told when none do',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        seedVaults(owner.bot.player.uuid, 1);
        const dialogs = new Dialogs(owner);

        await placeFirstVault(owner, dialogs, x + 2, z + 2);
        await expect(owner).toHaveReceivedMessage('placed.');
        const list = await dialogTitled(dialogs, 'Browse Vaults');
        expect(list.body).toContain('(1 vaults)');

        list.click(list.button('Vault #').label);
        (await dialogTitled(dialogs, 'Vault Action')).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);
        await rightClick(owner, x + 3, GROUND, z + 2);
        await expect(owner).toHaveReceivedMessage('That was your last vault.');
        expect(vaultCount(owner)).toBe(0);
    });

test('a vault with no items is placed as an empty chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x, z);
        seedVaults(player.bot.player.uuid, 1);

        await placeFirstVault(player, new Dialogs(player), x + 2, z + 2);

        await expect(player).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x + 2, FLOOR, z + 2, 'chest', 'empty-vault-placed');
        await server.execute(`execute unless items block ${x + 2} ${FLOOR} ${z + 2} container.* * run say placed-chest-is-empty`);
        await expect(server).toHaveReceivedMessage('placed-chest-is-empty');
    });

test('a vault whose stored block no longer exists, or whose block state does not parse, is still placed',
    { requires: { consoleOutput: 'full' } }, async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x, z);
        const owner = player.bot.player.uuid;
        const dialogs = new Dialogs(player);

        seedVaults(owner, 1);
        // An item, not a block: what an old version could store.
        sql(`UPDATE Vault SET material = 'DIAMOND' WHERE ownerUuid = '${owner}'`);
        await placeFirstVault(player, dialogs, x + 2, z + 2);
        await expect(player).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x + 2, FLOOR, z + 2, 'chest', 'item-material-placed-as-chest');

        seedVaults(owner, 1);
        sql(`UPDATE Vault SET blockData = 'minecraft:chest[facing=sideways]' WHERE ownerUuid = '${owner}'`);
        await placeFirstVault(player, dialogs, x + 3, z + 4);
        await expect(player).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x + 3, FLOOR, z + 4, 'chest', 'bad-state-placed-as-chest');
        expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${owner}'`)).toEqual(['0']);
    });

test('a vault edited to hold more than its block can take drops the rest at the block when placed',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:hopper`);
        for (let slot = 0; slot < 5; slot++) {
            await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.${slot} with minecraft:cobblestone 64`);
        }
        await lock(owner, x + 2, z);
        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vaulted.');

        // The edit view has nine slots; a hopper has five. The owner adds a sixth stack.
        const dialogs = new Dialogs(owner);
        await server.execute(`minecraft:item replace entity ${owner.username} hotbar.0 with minecraft:emerald 11`);
        (await ownVaultActions(owner, dialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);
        const hotbarStart = window.slots.length - 9;
        await owner.bot.clickWindow(hotbarStart, 0, 1);
        await sleep(300);
        owner.bot.closeWindow(window);
        await sleep(800);

        (await dialogTitled(dialogs, 'Vault Action')).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);
        await rightClick(owner, x + 2, GROUND, z + 3);
        await expect(owner).toHaveReceivedMessage('Vault placed.');

        await expectBlock(server, x + 2, FLOOR, z + 3, 'hopper', 'hopper-back');
        await expectItem(server, x + 2, FLOOR, z + 3, 4, 'cobblestone', 'hopper-full');
        // The emeralds did not fit and were not lost: they lie at the block.
        await server.execute(`execute if entity @e[type=minecraft:item,x=${x + 2},y=${FLOOR},z=${z + 3},distance=..3,nbt={Item:{id:"minecraft:emerald"}}] run say surplus-dropped-at-the-block`);
        await expect(server).toHaveReceivedMessage('surplus-dropped-at-the-block');
    });

test('the owner and staff placing one vault at the same moment place it once',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const ownerDialogs = new Dialogs(owner);
        const staffDialogs = new Dialogs(player);
        (await ownVaultActions(owner, ownerDialogs)).click('Place Block');
        (await dialogTitled(ownerDialogs, 'Vault Placement')).click('Start Placement');
        (await adminVaultActions(player, staffDialogs, owner.username)).click('Place Block');
        (await dialogTitled(staffDialogs, 'Vault Placement')).click('Start Placement');
        await sleep(400);

        await Promise.all([rightClick(owner, x + 2, GROUND, z + 2), rightClick(player, x + 4, GROUND, z + 2)]);
        await sleep(2500);

        // One of the two spots has the chest with the items; the other has nothing.
        await server.execute(`execute if block ${x + 2} ${FLOOR} ${z + 2} minecraft:chest unless block ${x + 4} ${FLOOR} ${z + 2} minecraft:chest run say placed-once`);
        await server.execute(`execute if block ${x + 4} ${FLOOR} ${z + 2} minecraft:chest unless block ${x + 2} ${FLOOR} ${z + 2} minecraft:chest run say placed-once`);
        await expect(server).toHaveReceivedMessage('placed-once');
        expect(vaultCount(owner)).toBe(0);
    });

test('a vault open for editing cannot be placed until the editor closes it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const ownerDialogs = new Dialogs(owner);
        const staffDialogs = new Dialogs(player);
        (await ownVaultActions(owner, ownerDialogs)).click('Open Inventory', { ACTION: 'edit' });
        const window = await openWindow(owner);

        (await adminVaultActions(player, staffDialogs, owner.username)).click('Place Block');
        (await dialogTitled(staffDialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);
        await rightClick(player, x + 2, GROUND, z + 2);
        await expect(player).toHaveReceivedMessage('Someone else has that vault open');
        await expectBlock(server, x + 2, FLOOR, z + 2, 'air', 'not-placed-while-edited');

        owner.bot.closeWindow(window);
        await sleep(600);
        expect(vaultCount(owner)).toBe(1);
    });
