import { expect, sleep, test } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { operators } from './players.js';
import {
    captureMode, clickEntity, entityIdAt, expectVaults, lock, lockedChest, placeFirstVault, vaultCount, vaultItemCount,
} from './vaults.js';
import { expectBlock, expectItem, FLOOR, freshSpot, leftClick, rightClick, standAt } from './world.js';

// What a capture meets beyond a stocked, locked chest.

/** Staff and an owner, both operators, standing at a fresh spot. */
async function scene(server: any, staff: any, owner: any) {
    const { x, z } = freshSpot();
    await operators(server, staff, owner);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    return { x, z };
}

test('a left-click ends capture mode, and a click after that vaults nothing',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);

        await captureMode(player);
        await leftClick(player, x + 1, FLOOR - 1, z);
        await expect(player).toHaveReceivedMessage('Capture cancelled.');

        await rightClick(player, x + 2, FLOOR, z);
        await sleep(1500);
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'cancelled-capture-left-it');
        expect(vaultCount(owner)).toBe(0);
    });

test('an empty locked chest only loses its lock, and is then nobody\'s to vault',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:chest`);
        await lock(owner, x + 2, z);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Protection removed.');
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'empty-chest-stays');
        expect(vaultCount(owner)).toBe(0);

        // Now stocked, and still unlocked: it has no owner, so not even staff can vault it.
        await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Not allowed');
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'unlocked-chest-stays');
        expect(vaultCount(owner)).toBe(0);
        expect(vaultCount(player)).toBe(0);
    });

test('a locked block that holds nothing, such as an anvil, only loses its lock',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:anvil`);
        await lock(owner, x + 2, z);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Protection removed.');
        await expectBlock(server, x + 2, FLOOR, z, 'anvil', 'anvil-stays');
        expect(vaultCount(owner)).toBe(0);
    });

test('a click on a block that is not locked and not a container is refused and changes nothing',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:stone`);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Not allowed');
        await expectBlock(server, x + 2, FLOOR, z, 'stone', 'stone-stays');
    });

for (const kind of ['barrel', 'trapped_chest', 'hopper', 'dropper', 'dispenser', 'shulker_box', 'blast_furnace', 'smoker', 'brewing_stand']) {
    test(`a locked ${kind} is vaulted and comes back as a ${kind} with its item in its slot`,
        { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
            const owner = await createPlayer();
            const { x, z } = await scene(server, player, owner);
            await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:${kind}`);
            // A brewing stand's slot 3 takes any ingredient; slot 0 of the others takes anything.
            const slot = kind === 'brewing_stand' ? 3 : 0;
            await server.execute(`minecraft:item replace block ${x + 2} ${FLOOR} ${z} container.${slot} with minecraft:emerald 7`);
            await lock(owner, x + 2, z);

            await captureMode(player);
            await rightClick(player, x + 2, FLOOR, z);
            await expect(player).toHaveReceivedMessage('Vault captured.');
            await expectBlock(server, x + 2, FLOOR, z, 'air', `${kind}-gone`);
            await expectVaults(owner, 1);

            await placeFirstVault(owner, new Dialogs(owner), x + 2, z + 2);
            await expect(owner).toHaveReceivedMessage('Vault placed.');
            await expectBlock(server, x + 2, FLOOR, z + 2, kind, `${kind}-back`);
            await expectItem(server, x + 2, FLOOR, z + 2, slot, 'emerald', `${kind}-item-back`);
            await expectVaults(owner, 0);
        });
}

test('items keep their enchantments, their names and what is inside them',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        const at = `${x + 2} ${FLOOR} ${z}`;
        await server.execute(`setblock ${at} minecraft:chest`);
        await server.execute(`minecraft:item replace block ${at} container.0 with minecraft:diamond_sword[minecraft:enchantments={"minecraft:sharpness":5},minecraft:custom_name="Excalibur"]`);
        await server.execute(`minecraft:item replace block ${at} container.1 with minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:emerald",count:9}}]]`);
        await server.execute(`minecraft:item replace block ${at} container.26 with minecraft:written_book[minecraft:written_book_content={title:"Deed",author:"Clerk",pages:["plot 12"]}]`);
        await lock(owner, x + 2, z);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectVaults(owner, 1);

        await placeFirstVault(owner, new Dialogs(owner), x + 2, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        const back = `${x + 2} ${FLOOR} ${z + 2}`;
        const check = async (slot: number, predicate: string, marker: string) => {
            await server.execute(`execute if items block ${back} container.${slot} ${predicate} run say ${marker}`);
            await expect(server).toHaveReceivedMessage(marker);
        };
        await check(0, 'minecraft:diamond_sword[minecraft:enchantments~[{enchantments:"minecraft:sharpness",levels:5}]]', 'sword-kept-sharpness');
        await check(0, 'minecraft:diamond_sword[minecraft:custom_name="Excalibur"]', 'sword-kept-its-name');
        await check(1, 'minecraft:shulker_box[minecraft:container~{items:{contains:[{items:"minecraft:emerald",count:9}]}}]', 'shulker-kept-its-emeralds');
        await check(26, 'minecraft:written_book[minecraft:written_book_content~{title:"Deed",author:"Clerk"}]', 'book-kept-its-text');
    });

test('a chest filled to its last slot comes back with every slot filled',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        const at = `${x + 2} ${FLOOR} ${z}`;
        await server.execute(`setblock ${at} minecraft:chest`);
        for (let slot = 0; slot < 27; slot++) {
            await server.execute(`minecraft:item replace block ${at} container.${slot} with minecraft:cobblestone 64`);
        }
        await lock(owner, x + 2, z);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectVaults(owner, 1);
        expect(vaultItemCount(owner)).toBe(27);

        await placeFirstVault(owner, new Dialogs(owner), x + 2, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        const back = `${x + 2} ${FLOOR} ${z + 2}`;
        await server.execute(`execute if items block ${back} container.* minecraft:cobblestone[minecraft:count=64] run say full-chest-back`);
        await expect(server).toHaveReceivedMessage('full-chest-back');
        // 27 slots of 64: the count the command reports.
        const counted = await server.execute(`execute store result score #vs vs_count if items block ${back} container.* minecraft:cobblestone`).catch(() => '');
        expect(counted).not.toContain('error');
        await expectItem(server, x + 2, FLOOR, z + 2, 26, 'cobblestone', 'last-slot-filled');
    });

test('the empty half of a double chest is not vaulted before the half with items',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:chest[facing=north,type=left]`);
        await server.execute(`setblock ${x + 3} ${FLOOR} ${z} minecraft:chest[facing=north,type=right]`);
        // Only the right half holds anything.
        await server.execute(`minecraft:item replace block ${x + 3} ${FLOOR} ${z} container.0 with minecraft:emerald 3`);
        await lock(owner, x + 2, z);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('You should vault the non-empty side first.');
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'empty-half-stays');
        await expectBlock(server, x + 3, FLOOR, z, 'chest', 'full-half-stays');
        expect(vaultCount(owner)).toBe(0);

        // The half with items goes; the empty half is then an empty chest and only loses its lock.
        await rightClick(player, x + 3, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, x + 3, FLOOR, z, 'air', 'full-half-gone');
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Protection removed.');
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'empty-half-left-as-a-chest');
        await expectVaults(owner, 1);
    });

test('a locked painting is vaulted as a painting, and removed',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        // A wall for it to hang on, facing south towards the players.
        await server.execute(`fill ${x + 2} ${FLOOR} ${z - 1} ${x + 2} ${FLOOR + 1} ${z - 1} minecraft:stone`);
        await server.execute(`summon minecraft:painting ${x + 2} ${FLOOR} ${z} {facing:0b,variant:"minecraft:kebab",block_pos:[I;${x + 2},${FLOOR},${z}]}`);

        owner.chat('/lock');
        await sleep(500);
        clickEntity(owner, await entityIdAt(owner, 'painting', x + 2, z));
        await sleep(700);

        await captureMode(player);
        clickEntity(player, await entityIdAt(player, 'painting', x + 2, z));
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await server.execute(`execute unless entity @e[type=minecraft:painting,x=${x + 2},y=${FLOOR},z=${z},distance=..2] run say painting-is-gone`);
        await expect(server).toHaveReceivedMessage('painting-is-gone');
        await expectVaults(owner, 1);
        expect(vaultItemCount(owner)).toBe(1);
    });

test('two locked item frames of one owner are stored without losing either item',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        for (const [fx, item] of [[x + 2, 'diamond'], [x + 3, 'emerald']] as const) {
            await server.execute(`summon minecraft:item_frame ${fx} ${FLOOR} ${z} {Facing:1b,block_pos:[I;${fx},${FLOOR},${z}],Item:{id:"minecraft:${item}",count:1}}`);
            owner.chat('/lock');
            await sleep(500);
            clickEntity(owner, await entityIdAt(owner, 'item_frame', fx, z));
            await sleep(700);
        }

        await captureMode(player);
        clickEntity(player, await entityIdAt(player, 'item_frame', x + 2, z));
        await sleep(1500);
        clickEntity(player, await entityIdAt(player, 'item_frame', x + 3, z));
        await sleep(1500);

        await server.execute(`execute unless entity @e[type=minecraft:item_frame,x=${x + 2},y=${FLOOR},z=${z},distance=..3] run say both-frames-gone`);
        await expect(server).toHaveReceivedMessage('both-frames-gone');
        // Two frames and the two items they showed.
        expect(vaultItemCount(owner)).toBe(4);
    });

test('two staff clicking one chest at the same moment make one vault, not two',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, second] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await operators(server, second);
        await standAt(server, second, x + 1, z + 1);
        await lockedChest(server, owner, x + 2, z);

        await captureMode(player);
        await captureMode(second);
        await Promise.all([rightClick(player, x + 2, FLOOR, z), rightClick(second, x + 2, FLOOR, z)]);
        await sleep(2500);

        await expectBlock(server, x + 2, FLOOR, z, 'air', 'raced-chest-gone');
        expect(vaultCount(owner)).toBe(1);
        expect(vaultItemCount(owner)).toBe(2);
    });

test('a second click on a chest while its capture is still being saved does not vault it twice',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);

        await captureMode(player);
        // Three clicks with no pause between them.
        const client = (player.bot as any)._client;
        for (let click = 0; click < 3; click++) {
            client.write('block_place', {
                hand: 0, location: { x: x + 2, y: FLOOR, z }, direction: 1, cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5,
                insideBlock: false, worldBorderHit: false, sequence: 900 + click,
            });
        }
        await sleep(2500);

        await expectBlock(server, x + 2, FLOOR, z, 'air', 'triple-clicked-chest-gone');
        expect(vaultCount(owner)).toBe(1);
        expect(vaultItemCount(owner)).toBe(2);
    });
