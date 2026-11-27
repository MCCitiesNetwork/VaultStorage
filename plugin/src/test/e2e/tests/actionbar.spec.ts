import { expect, sleep, test } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { MENU, operators, ordinary } from './players.js';
import { around, defineRegion } from './realty.js';
import { ActionBars, captureMode, dialogTitled, lockedChest, lookAtBlock, ownVaultActions, vaultFor } from './vaults.js';
import { FLOOR, freshSpot, GROUND, standAt } from './world.js';

// The line above the hotbar in capture and placement mode: it says, before the click, whether
// what the player is looking at can be vaulted or placed on, and why not.

test('capture mode says whose a locked chest is and that staff may vault it',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);
        const bars = new ActionBars(player);

        await captureMode(player);
        await bars.next('Capture mode');
        await lookAtBlock(player, x + 2, FLOOR, z);

        const line = await bars.next('Vaultable:');
        expect(line).toContain(`Owner: ${owner.username}`);
        expect(line).toContain('Vaultable: yes');
        expect(line).toContain('ADMIN');
    });

test('capture mode says why an unlocked chest cannot be vaulted',
    async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x, z);
        await server.execute(`setblock ${x + 2} ${FLOOR} ${z} minecraft:chest`);
        const bars = new ActionBars(player);

        await captureMode(player);
        await lookAtBlock(player, x + 2, FLOOR, z);

        const line = await bars.next('Vaultable:');
        expect(line).toContain('Owner: unprotected');
        expect(line).toContain('Vaultable: no');
        expect(line).toContain('Reason:');
    });

test('capture mode tells a region member that a co-member\'s chest is not theirs to vault, and names the region',
    async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await standAt(server, member, x + 1, z + 1);
        await lockedChest(server, owner, x + 3, z + 1);
        const name = `bar_${x}`;
        await defineRegion(player, name, around(x, z));
        player.chat(`/rg addmember ${name} ${member.username} ${owner.username}`);
        await expect(player).toHaveReceivedMessage(`'${name}' updated with new members`);
        await ordinary(server, member, [MENU, 'vaultstorage.action.capture']);
        const bars = new ActionBars(member);

        await captureMode(member);
        await lookAtBlock(member, x + 3, FLOOR, z + 1);

        const line = await bars.next('Vaultable:');
        expect(line).toContain('Vaultable: no');
        expect(line).toContain(`${owner.username} owns this block and is a member of '${name}'`);
        expect(line).not.toContain('ADMIN');
    });

test('capture mode tells a player outside every region that the block is in none',
    async ({ player, createPlayer, server }) => {
        const [owner, wanderer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, owner, x, z + 1);
        await standAt(server, wanderer, x + 1, z + 1);
        await lockedChest(server, owner, x + 3, z + 1);
        await ordinary(server, wanderer, [MENU, 'vaultstorage.action.capture']);
        const bars = new ActionBars(wanderer);

        await captureMode(wanderer);
        await lookAtBlock(wanderer, x + 3, FLOOR, z + 1);

        expect(await bars.next('Vaultable:')).toContain('block is not inside any protected region');
    });

test('placement mode says whether the block looked at can be placed on',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await vaultFor(server, player, owner);
        const dialogs = new Dialogs(owner);
        const bars = new ActionBars(owner);
        (await ownVaultActions(owner, dialogs)).click('Place Block');
        (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
        await sleep(300);

        await lookAtBlock(owner, x + 2, GROUND, z + 1);
        expect(await bars.next('place it here')).toContain('You can place it here.');

        // An ordinary player on land that is nobody's may not place there.
        await ordinary(server, owner, [MENU]);
        expect(await bars.next('Not here.')).toContain('Not here.');
    });
