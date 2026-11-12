import { expect, sleep, test, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { lockedChest, ownVaultActions, dialogTitled } from './vaults.js';
import { expectBlock, FLOOR, freshSpot, GROUND, rightClick, standAt } from './world.js';

// What a player who is not an operator may do. Operators bypass every rule here, so these
// players get only the permissions a normal player would hold, through LuckPerms.

/** Gives a player who is not an operator the permissions to use the plugin. */
async function ordinaryPlayer(server: ServerWrapper, player: PlayerWrapper): Promise<void> {
    await server.execute(`deop ${player.username}`);
    for (const node of ['vaultstorage.user', 'vaultstorage.action.capture']) {
        await server.execute(`lp user ${player.username} permission set ${node} true`);
    }
    // LuckPerms applies a change a moment after the command returns.
    await sleep(700);
}

/** A WorldGuard region around a spot, defined by staff, with these members. */
async function regionAround(staff: PlayerWrapper, x: number, z: number, members: PlayerWrapper[]): Promise<string> {
    const region = `policy_${x}`;
    staff.chat(`//pos1 ${x - 5},-64,${z - 5}`);
    staff.chat(`//pos2 ${x + 5},-50,${z + 5}`);
    await sleep(300);
    staff.chat(`/rg define ${region}`);
    await expect(staff).toHaveReceivedMessage(region);
    for (const member of members) {
        staff.chat(`/rg addmember ${region} ${member.username}`);
        await expect(staff).toHaveReceivedMessage('updated with new members');
    }
    return region;
}

/** Staff, an owner with a locked chest, and a third player, all standing at a fresh spot. */
async function scene(server: ServerWrapper, staff: PlayerWrapper, owner: PlayerWrapper, actor: PlayerWrapper) {
    const { x, z } = freshSpot();
    await Promise.all([
        server.execute(`op ${staff.username}`),
        server.execute(`op ${owner.username}`),
    ]);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    await standAt(server, actor, x + 1, z + 1);
    await lockedChest(server, owner, x + 2, z);
    await ordinaryPlayer(server, actor);
    return { x, z, chestX: x + 2 };
}

test('a region member vaults the chest of someone who is not a member',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        await regionAround(player, x, z, [member]);

        member.chat('/vault capture');
        await expect(member).toHaveReceivedMessage('Capture session started');
        await rightClick(member, chestX, FLOOR, z);
        await expect(member).toHaveReceivedMessage('Vault captured.');
        await expectBlock(server, chestX, FLOOR, z, 'air', 'member-vaulted-it');
    });

test('a player who is not a member of the region cannot vault someone else\'s chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, stranger] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, stranger);
        await regionAround(player, x, z, []);

        stranger.chat('/vault capture');
        await expect(stranger).toHaveReceivedMessage('Capture session started');
        await rightClick(stranger, chestX, FLOOR, z);
        await expect(stranger).toHaveReceivedMessage('Not allowed');
        await expectBlock(server, chestX, FLOOR, z, 'chest', 'stranger-left-it');
    });

test('a member cannot vault the chest of another member',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        await regionAround(player, x, z, [member, owner]);

        member.chat('/vault capture');
        await expect(member).toHaveReceivedMessage('Capture session started');
        await rightClick(member, chestX, FLOOR, z);
        await expect(member).toHaveReceivedMessage('Not allowed');
        await expectBlock(server, chestX, FLOOR, z, 'chest', 'co-member-left-it');
    });

test('a chest outside any region cannot be vaulted by a player without the override',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, wanderer] = await Promise.all([createPlayer(), createPlayer()]);
        const { z, chestX } = await scene(server, player, owner, wanderer);

        wanderer.chat('/vault capture');
        await expect(wanderer).toHaveReceivedMessage('Capture session started');
        await rightClick(wanderer, chestX, FLOOR, z);
        await expect(wanderer).toHaveReceivedMessage('Not allowed');
        await expectBlock(server, chestX, FLOOR, z, 'chest', 'wilderness-left-it');
    });

test('a vault is placed only where its owner belongs to the region, or on their own claim',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);
        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture session started');
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');

        // The owner is now an ordinary player with no region and no claim.
        await server.execute(`deop ${owner.username}`);
        await server.execute(`lp user ${owner.username} permission set vaultstorage.user true`);
        await sleep(700);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await rightClick(owner, x, GROUND, z + 3);
        await expect(owner).toHaveReceivedMessage('must be a region member');
        await expectBlock(server, x, FLOOR, z + 3, 'air', 'not-placed-on-wild-land');

        // A GriefPrevention claim of their own is somewhere they may place. Placement mode is
        // still on: a refused click does not end it.
        await server.execute(`acb ${owner.username} 5000`);
        await owner.giveItem('golden_shovel');
        owner.chat('/claim 5');
        await sleep(1500);
        await rightClick(owner, x, GROUND, z + 3);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x, FLOOR, z + 3, 'chest', 'placed-on-own-claim');
    });
