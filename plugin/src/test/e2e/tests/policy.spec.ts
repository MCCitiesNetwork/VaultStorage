import { expect, sleep, test, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { lockedChest, ownVaultActions, dialogTitled } from './vaults.js';
import { expectBlock, FLOOR, freshSpot, GROUND, rightClick, standAt } from './world.js';

// What a player who is not an operator may do. Operators bypass every rule here, so these
// players get only the permissions a normal player would hold, through LuckPerms.

/** Gives a player who is not an operator the permissions to use the plugin. */
async function ordinaryPlayer(server: ServerWrapper, player: PlayerWrapper): Promise<void> {
    await server.execute(`deop ${player.username}`);
    for (const node of ['vaultstorage.command.vault', 'vaultstorage.action.capture']) {
        await server.execute(`lp user ${player.username} permission set ${node} true`);
    }
    // LuckPerms applies a change a moment after the command returns.
    await sleep(700);
}

interface Bounds { minX: number; maxX: number; minY: number; maxY: number; minZ: number; maxZ: number }

/** The whole height of the test world around a spot. */
function around(x: number, z: number): Bounds {
    return { minX: x - 5, maxX: x + 5, minY: -64, maxY: -50, minZ: z - 5, maxZ: z + 5 };
}

/** A WorldGuard region with these bounds, defined by staff, with these members. */
async function region(staff: PlayerWrapper, name: string, bounds: Bounds, members: PlayerWrapper[]): Promise<string> {
    staff.chat(`//pos1 ${bounds.minX},${bounds.minY},${bounds.minZ}`);
    staff.chat(`//pos2 ${bounds.maxX},${bounds.maxY},${bounds.maxZ}`);
    await sleep(300);
    staff.chat(`/rg define ${name}`);
    await expect(staff).toHaveReceivedMessage(name);
    if (members.length > 0) {
        // One command for all of them: WorldGuard answers once, when every member is in.
        staff.chat(`/rg addmember ${name} ${members.map((member) => member.username).join(' ')}`);
        // Named, so the answer about an earlier region is not taken for this one's.
        await expect(staff).toHaveReceivedMessage(`'${name}' updated with new members`);
    }
    return name;
}

/** A WorldGuard region around a spot, defined by staff, with these members. */
async function regionAround(staff: PlayerWrapper, x: number, z: number, members: PlayerWrapper[]): Promise<string> {
    return region(staff, `policy_${x}`, around(x, z), members);
}

/** The actor right-clicks the chest in capture mode and is refused; the chest stays. */
async function expectRefused(server: ServerWrapper, actor: PlayerWrapper, x: number, z: number, marker: string): Promise<void> {
    actor.chat('/vault capture');
    await expect(actor).toHaveReceivedMessage('Capture mode is on');
    await rightClick(actor, x, FLOOR, z);
    await expect(actor).toHaveReceivedMessage('You may not vault that');
    await expectBlock(server, x, FLOOR, z, 'chest', marker);
}

/** The actor right-clicks the chest in capture mode and it is vaulted. */
async function expectVaulted(server: ServerWrapper, actor: PlayerWrapper, x: number, z: number, marker: string): Promise<void> {
    actor.chat('/vault capture');
    await expect(actor).toHaveReceivedMessage('Capture mode is on');
    await rightClick(actor, x, FLOOR, z);
    await expect(actor).toHaveReceivedMessage('Vaulted.');
    await expectBlock(server, x, FLOOR, z, 'air', marker);
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
        await expect(member).toHaveReceivedMessage('Capture mode is on');
        await rightClick(member, chestX, FLOOR, z);
        await expect(member).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, chestX, FLOOR, z, 'air', 'member-vaulted-it');
    });

test('a player who is not a member of the region cannot vault someone else\'s chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, stranger] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, stranger);
        await regionAround(player, x, z, []);

        stranger.chat('/vault capture');
        await expect(stranger).toHaveReceivedMessage('Capture mode is on');
        await rightClick(stranger, chestX, FLOOR, z);
        await expect(stranger).toHaveReceivedMessage('You may not vault that');
        await expectBlock(server, chestX, FLOOR, z, 'chest', 'stranger-left-it');
    });

test('a member cannot vault the chest of another member',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        await regionAround(player, x, z, [member, owner]);

        member.chat('/vault capture');
        await expect(member).toHaveReceivedMessage('Capture mode is on');
        await rightClick(member, chestX, FLOOR, z);
        await expect(member).toHaveReceivedMessage('You may not vault that');
        await expectBlock(server, chestX, FLOOR, z, 'chest', 'co-member-left-it');
    });

test('a chest outside any region cannot be vaulted by a player without the override',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, wanderer] = await Promise.all([createPlayer(), createPlayer()]);
        const { z, chestX } = await scene(server, player, owner, wanderer);

        wanderer.chat('/vault capture');
        await expect(wanderer).toHaveReceivedMessage('Capture mode is on');
        await rightClick(wanderer, chestX, FLOOR, z);
        await expect(wanderer).toHaveReceivedMessage('You may not vault that');
        await expectBlock(server, chestX, FLOOR, z, 'chest', 'wilderness-left-it');
    });

// Regions that overlap. The chest is at x+2. West is x-5..x+3 and east is x+1..x+7, so the
// chest is in both.

test('where two regions overlap, a member of one vaults the chest of someone in neither',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        await region(player, `west_${x}`, { ...around(x, z), maxX: x + 3 }, [member]);
        await region(player, `east_${x}`, { ...around(x, z), minX: x + 1, maxX: x + 7 }, []);

        await expectVaulted(server, member, chestX, z, 'overlap-vaulted');
    });

test('where two regions overlap, a member of one cannot vault the chest of the other\'s member',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        await region(player, `west_${x}`, { ...around(x, z), maxX: x + 3 }, [member]);
        await region(player, `east_${x}`, { ...around(x, z), minX: x + 1, maxX: x + 7 }, [owner]);

        await expectRefused(server, member, chestX, z, 'overlap-left');
    });

test('a member of a region that only touches the chest\'s region has no say over the chest',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        // The chest at x+2 is in east alone; west ends one block before it.
        await region(player, `west_${x}`, { ...around(x, z), maxX: x + 1 }, [member]);
        await region(player, `east_${x}`, { ...around(x, z), minX: x + 2, maxX: x + 7 }, []);

        await expectRefused(server, member, chestX, z, 'next-door-left');
    });

// Regions on one footprint, one above the other. The chest is on the floor, in the lower one.

test('a member of the region above cannot vault a chest in the region below',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        await region(player, `ground_${x}`, { ...around(x, z), maxY: FLOOR + 1 }, []);
        await region(player, `upstairs_${x}`, { ...around(x, z), minY: FLOOR + 2 }, [member]);

        await expectRefused(server, member, chestX, z, 'below-left');
    });

test('a member of the region below vaults a chest in it, whoever belongs to the region above',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, member);
        await region(player, `ground_${x}`, { ...around(x, z), maxY: FLOOR + 1 }, [member]);
        // The chest's owner belongs to the region above, which the chest is not in.
        await region(player, `upstairs_${x}`, { ...around(x, z), minY: FLOOR + 2 }, [owner]);

        await expectVaulted(server, member, chestX, z, 'below-vaulted');
    });

// A plot inside a city.

test('a member of the city cannot vault a chest in a plot inside it',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, citizen] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, chestX } = await scene(server, player, owner, citizen);
        await region(player, `city_${x}`, { ...around(x, z), minX: x - 20, maxX: x + 20, minZ: z - 20, maxZ: z + 20 }, [citizen]);
        await region(player, `plot_${x}`, around(x, z), []);

        await expectRefused(server, citizen, chestX, z, 'plot-in-city-left');
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
        await expect(player).toHaveReceivedMessage('Capture mode is on');
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vaulted.');

        // The owner is now an ordinary player with no region and no claim.
        await server.execute(`deop ${owner.username}`);
        await server.execute(`lp user ${owner.username} permission set vaultstorage.command.vault true`);
        await sleep(700);
        const dialogs = new Dialogs(owner);
        (await ownVaultActions(owner, dialogs)).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await rightClick(owner, x, GROUND, z + 3);
        await expect(owner).toHaveReceivedMessage('Place it on land you own');
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
