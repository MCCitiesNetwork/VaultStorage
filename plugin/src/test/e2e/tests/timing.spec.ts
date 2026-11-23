import { expect, sleep, test } from '@plugwright/runner';
import { holdVaultTables } from './database.js';
import { Dialogs } from './dialogs.js';
import { operators } from './players.js';
import {
    captureMode, clickEntity, dialogTitled, entityIdAt, lockedChest, ownVaultActions, vaultCount, vaultFor, vaultItemCount,
} from './vaults.js';
import { expectBlock, expectItem, FLOOR, freshSpot, GROUND, rightClick, standAt } from './world.js';

// What happens in the world while the database is still answering. A capture stores, then
// checks that the container is as it was, then removes it; a placement claims, then places.
// The database step is held open here so the world can change in the middle of it.

/** Long enough to change the world during it, short enough not to slow the suite. */
const HOLD_SECONDS = 4;

async function scene(server: any, staff: any, owner: any) {
    const { x, z } = freshSpot();
    await operators(server, staff, owner);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    return { x, z };
}

test('a chest that gains an item while its capture is being saved is left alone, with nothing stored',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);
        await captureMode(player);

        holdVaultTables(HOLD_SECONDS);
        await sleep(500);
        await rightClick(player, x + 2, FLOOR, z);
        await sleep(1000);
        await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.5 with minecraft:emerald 9`);

        await expect(player).toHaveReceivedMessage('That container changed while it was being saved', { timeout: 15000 });
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'changed-chest-stays');
        await expectItem(server, x + 2, FLOOR, z, 0, 'diamond', 'changed-chest-keeps-diamonds');
        await expectItem(server, x + 2, FLOOR, z, 5, 'emerald', 'changed-chest-keeps-the-new-item');
        // The copy that was stored has been taken back: the items are in one place only.
        await sleep(1000);
        expect(vaultCount(owner)).toBe(0);

        // Unchanged this time, the same click vaults it, emeralds and all.
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        expect(vaultItemCount(owner)).toBe(3);
    });

test('a chest that loses an item while its capture is being saved is left alone, and the item is not duplicated',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);
        await captureMode(player);

        holdVaultTables(HOLD_SECONDS);
        await sleep(500);
        await rightClick(player, x + 2, FLOOR, z);
        await sleep(1000);
        // Someone takes the diamonds out in the meantime.
        await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.0 with minecraft:air`);

        await expect(player).toHaveReceivedMessage('That container changed while it was being saved', { timeout: 15000 });
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'emptied-chest-stays');
        await sleep(1000);
        expect(vaultCount(owner)).toBe(0);
    });

test('a chest that is broken while its capture is being saved leaves no vault behind',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);
        await captureMode(player);

        holdVaultTables(HOLD_SECONDS);
        await sleep(500);
        await rightClick(player, x + 2, FLOOR, z);
        await sleep(1000);
        // Broken, as a player would: its items drop on the ground.
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:air destroy`);

        await expect(player).toHaveReceivedMessage('That container changed while it was being saved', { timeout: 15000 });
        await sleep(1000);
        // The items are on the ground, so a vault holding them too would double them.
        expect(vaultCount(owner)).toBe(0);
    });

test('further clicks on a chest whose capture is still being saved do nothing',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);
        await captureMode(player);

        holdVaultTables(HOLD_SECONDS);
        await sleep(500);
        for (let click = 0; click < 4; click++) {
            await rightClick(player, x + 2, FLOOR, z);
            await sleep(400);
        }

        await expect(player).toHaveReceivedMessage('Vault captured.', { timeout: 15000 });
        await sleep(1500);
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'slow-capture-finished');
        expect(vaultCount(owner)).toBe(1);
        expect(vaultItemCount(owner)).toBe(2);
    });

test('an item frame whose item is swapped while its capture is being saved is left alone',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await server.execute(`summon minecraft:item_frame ${x + 2} ${FLOOR} ${z} {Facing:1b,block_pos:[I;${x + 2},${FLOOR},${z}],Item:{id:"minecraft:diamond",count:1}}`);
        owner.chat('/lock');
        await sleep(500);
        clickEntity(owner, await entityIdAt(owner, 'item_frame', x + 2, z));
        await sleep(700);
        await captureMode(player);

        holdVaultTables(HOLD_SECONDS);
        await sleep(500);
        clickEntity(player, await entityIdAt(player, 'item_frame', x + 2, z));
        await sleep(1000);
        await server.execute(`data modify entity @e[type=minecraft:item_frame,x=${x + 2},y=${FLOOR},z=${z},distance=..2,limit=1] Item set value {id:"minecraft:emerald",count:1}`);

        await expect(player).toHaveReceivedMessage('That container changed while it was being saved', { timeout: 15000 });
        await server.execute(`execute if entity @e[type=minecraft:item_frame,x=${x + 2},y=${FLOOR},z=${z},distance=..2] run say swapped-frame-stays`);
        await expect(server).toHaveReceivedMessage('swapped-frame-stays');
        await sleep(1000);
        expect(vaultItemCount(owner)).toBe(0);
    });

test('a block put at the target while a placement is being claimed refuses the placement and keeps the vault usable',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);

        holdVaultTables(HOLD_SECONDS);
        await sleep(500);
        await rightClick(owner, x + 2, GROUND, z + 2);
        await sleep(1000);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z + 2} minecraft:stone`);

        await expect(owner).toHaveReceivedMessage('Target not empty.', { timeout: 15000 });
        await expectBlock(server, x + 2, FLOOR, z + 2, 'stone', 'late-stone-not-replaced');
        expect(vaultCount(owner)).toBe(1);
        // Released, not left in use: it goes down somewhere else straight away.
        (await dialogTitled(dialogs, 'Vault Action')).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);
        await rightClick(owner, x + 3, GROUND, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
    });

test('a list opened while the database is slow shows a loading screen and then the list',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        owner.chat('/vault');
        const menu = await dialogTitled(dialogs, 'Vault Capture');

        holdVaultTables(3);
        await sleep(500);
        menu.click('Browse Vaults');

        expect((await dialogs.next()).title).toBe('Loading');
        expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('(1 vaults)');
    });
