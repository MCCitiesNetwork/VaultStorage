import { expect, sleep, test, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { query, seedVaults } from './database.js';
import { Dialogs } from './dialogs.js';
import { MENU, operators, ordinary } from './players.js';
import { around, defineRegion } from './realty.js';
import { captureMode, clickEntity, entityIdAt, expectVaults, lock, lockedChest, placeFirstVault, vaultCount } from './vaults.js';
import { expectBlock, FLOOR, freshSpot, rightClick, standAt } from './world.js';

// Ownership rules at their edges: the override permission, a region's owners, a player's own
// container, child regions, signs, and claims.

const CAPTURE = 'vaultstorage.action.capture';
const OVERRIDE = 'vaultstorage.bypass.region';

/** Staff, an owner with a locked chest at x+2, and an actor who is not an operator. */
async function scene(server: ServerWrapper, staff: PlayerWrapper, owner: PlayerWrapper, actor: PlayerWrapper, nodes: string[]) {
    const { x, z } = freshSpot();
    await operators(server, staff, owner);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    await standAt(server, actor, x + 1, z + 1);
    await lockedChest(server, owner, x + 2, z);
    await ordinary(server, actor, [MENU, CAPTURE, ...nodes]);
    return { x, z, chestX: x + 2 };
}

async function expectRefused(server: ServerWrapper, actor: PlayerWrapper, x: number, z: number, marker: string) {
    await captureMode(actor);
    await rightClick(actor, x, FLOOR, z);
    await expect(actor).toHaveReceivedMessage('Not allowed');
    await expectBlock(server, x, FLOOR, z, 'chest', marker);
}

async function expectVaulted(server: ServerWrapper, actor: PlayerWrapper, x: number, z: number, marker: string) {
    await captureMode(actor);
    await rightClick(actor, x, FLOOR, z);
    await expect(actor).toHaveReceivedMessage('Vault captured.');
    await expectBlock(server, x, FLOOR, z, 'air', marker);
}

test('the override permission lets a player who is not an operator vault a chest outside every region',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, actor] = await Promise.all([createPlayer(), createPlayer()]);
        const { z, chestX } = await scene(server, player, owner, actor, [OVERRIDE]);

        await expectVaulted(server, actor, chestX, z, 'override-vaulted-in-the-wild');
        await expectVaults(owner, 1);
        expect(vaultCount(actor)).toBe(0);
    });

test('the override permission lets a player vault in a region they have no part in',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, actor] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, actor, [OVERRIDE]);
        await defineRegion(player, `rule_${x}`, around(x, z));

        await expectVaulted(server, actor, chestX, z, 'override-vaulted-in-a-strange-region');
    });

test('a region\'s owner counts as part of it, the same as a member',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, landlord] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, landlord, []);
        const name = `rule_${x}`;
        await defineRegion(player, name, around(x, z));
        player.chat(`/rg addowner ${name} ${landlord.username}`);
        await expect(player).toHaveReceivedMessage(`'${name}' updated with new owners`);

        await expectVaulted(server, landlord, chestX, z, 'region-owner-vaulted-it');
    });

test('a chest whose owner is the region\'s owner is not a member\'s to vault',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member, []);
        const name = `rule_${x}`;
        await defineRegion(player, name, around(x, z));
        player.chat(`/rg addowner ${name} ${owner.username}`);
        await expect(player).toHaveReceivedMessage(`'${name}' updated with new owners`);
        player.chat(`/rg addmember ${name} ${member.username}`);
        await expect(player).toHaveReceivedMessage(`'${name}' updated with new members`);

        await expectRefused(server, member, chestX, z, 'landlords-chest-left');
    });

test('a player with no part in a region may vault their own chest there, and nobody else\'s',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, visitor] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, visitor, []);
        await server.execute(`op ${visitor.username}`);
        await lockedChest(server, visitor, x + 3, z + 1);
        await ordinary(server, visitor, [MENU, CAPTURE]);
        await defineRegion(player, `rule_${x}`, around(x, z));

        await expectRefused(server, visitor, chestX, z, 'someone-elses-chest-left');
        await rightClick(visitor, x + 3, FLOOR, z + 1);
        await expectVaults(visitor, 1);
        await expectBlock(server, x + 3, FLOOR, z + 1, 'air', 'own-chest-vaulted');
    });

test('a member cannot vault their own chest in their own region, even with the override',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner, member, [OVERRIDE]);
        await server.execute(`op ${member.username}`);
        await lockedChest(server, member, x + 3, z + 1);
        await ordinary(server, member, [MENU, CAPTURE, OVERRIDE]);
        const name = `rule_${x}`;
        await defineRegion(player, name, around(x, z));
        player.chat(`/rg addmember ${name} ${member.username}`);
        await expect(player).toHaveReceivedMessage(`'${name}' updated with new members`);

        await expectRefused(server, member, x + 3, z + 1, 'own-chest-in-own-region-left');
        expect(vaultCount(member)).toBe(0);
    });

test('a member of a region\'s WorldGuard parent is a member of the child, as WorldGuard has it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member, []);
        const parent = `parent_${x}`;
        const child = `child_${x}`;
        await defineRegion(player, parent, { ...around(x, z), minX: x - 20, maxX: x + 20, minZ: z - 20, maxZ: z + 20 });
        await defineRegion(player, child, around(x, z));
        // Realty sets a subregion's parent this way. Without it, a region that merely lies
        // inside another gives the outer region's members no say: see policy.spec.ts.
        player.chat(`/rg setparent ${child} ${parent}`);
        await sleep(500);
        player.chat(`/rg addmember ${parent} ${member.username}`);
        await expect(player).toHaveReceivedMessage(`'${parent}' updated with new members`);

        await expectVaulted(server, member, chestX, z, 'inherited-member-vaulted-it');
    });

test('a tenant of a child region keeps their chest from the parent region\'s members',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member, []);
        const parent = `parent_${x}`;
        const child = `child_${x}`;
        await defineRegion(player, parent, { ...around(x, z), minX: x - 20, maxX: x + 20, minZ: z - 20, maxZ: z + 20 });
        await defineRegion(player, child, around(x, z));
        player.chat(`/rg setparent ${child} ${parent}`);
        await sleep(500);
        player.chat(`/rg addmember ${parent} ${member.username}`);
        await expect(player).toHaveReceivedMessage(`'${parent}' updated with new members`);
        player.chat(`/rg addmember ${child} ${owner.username}`);
        await expect(player).toHaveReceivedMessage(`'${child}' updated with new members`);

        await expectRefused(server, member, chestX, z, 'tenants-chest-left');
    });

test('a locked plain sign on a locked chest loses its lock, and the chest behind it is vaulted',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);
        // A wall sign on the chest's south face.
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z + 1} minecraft:oak_wall_sign[facing=south]`);
        await lock(owner, x + 2, z + 1);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z + 1);

        await expect(player).toHaveReceivedMessage('Sign protection removed.');
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'chest-behind-the-sign-vaulted');
        await expectVaults(owner, 1);
    });

test('a sign with nothing behind it vaults nothing',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:stone`);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z + 1} minecraft:oak_wall_sign[facing=south]`);
        await lock(owner, x + 2, z + 1);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z + 1);
        await sleep(1500);

        await expectBlock(server, x + 2, FLOOR, z, 'stone', 'stone-behind-the-sign-stays');
        expect(vaultCount(owner)).toBe(0);
    });

test('an empty locked item frame only loses its lock',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await server.execute(`summon minecraft:item_frame ${x + 2} ${FLOOR} ${z} {Facing:1b,block_pos:[I;${x + 2},${FLOOR},${z}]}`);
        owner.chat('/lock');
        await sleep(500);
        clickEntity(owner, await entityIdAt(owner, 'item_frame', x + 2, z));
        await sleep(700);

        await captureMode(player);
        clickEntity(player, await entityIdAt(player, 'item_frame', x + 2, z));

        await expect(player).toHaveReceivedMessage('Protection removed.');
        await server.execute(`execute if entity @e[type=minecraft:item_frame,x=${x + 2},y=${FLOOR},z=${z},distance=..2] run say empty-frame-stays`);
        await expect(server).toHaveReceivedMessage('empty-frame-stays');
        expect(vaultCount(owner)).toBe(0);
    });

test('a vault can be transferred to a player who is offline, and they find it when they return',
    async ({ player, createPlayer, server }) => {
        const heir = await createPlayer();
        await operators(server, player);
        seedVaults(player.bot.player.uuid, 1);
        const [vault] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${player.bot.player.uuid}'`);
        const heirName = heir.username;
        const heirUuid = heir.bot.player.uuid;
        heir.bot.quit();
        await sleep(1500);

        player.chat(`/vault transfer ${vault} ${heirName}`);

        await expect(player).toHaveReceivedMessage(`to ${heirUuid}`);
        expect(query(`SELECT ownerUuid FROM Vault WHERE uuid = '${vault}'`)).toEqual([heirUuid]);
    });

test('a vault is placed on a claim its owner is trusted on, and not on a stranger\'s claim',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, claimant] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await standAt(server, claimant, x, z + 3);
        await lockedChest(server, owner, x + 2, z);
        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        // The claimant claims the land around all three of them.
        await server.execute(`acb ${claimant.username} 5000`);
        await claimant.giveItem('golden_shovel');
        claimant.chat('/claim 6');
        await sleep(1500);
        await ordinary(server, owner, [MENU]);
        const dialogs = new Dialogs(owner);

        await placeFirstVault(owner, dialogs, x + 1, z + 3);
        await expect(owner).toHaveReceivedMessage('must be a region member');
        await expectBlock(server, x + 1, FLOOR, z + 3, 'air', 'not-placed-on-a-strangers-claim');

        claimant.chat(`/trust ${owner.username}`);
        await sleep(1000);
        await rightClick(owner, x + 1, FLOOR - 1, z + 3);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x + 1, FLOOR, z + 3, 'chest', 'placed-where-trusted');
    });
