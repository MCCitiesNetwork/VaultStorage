import { expect, sleep, test, type PlayerWrapper } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';
import { MENU, operators, ordinary } from './players.js';
import { buy, definePolygon, freehold, scene, SWEEP_HAS_RUN, vaultsOf } from './realty.js';
import { captureMode, dialogTitled, lockedChest } from './vaults.js';
import { expectBlock, FLOOR, freshSpot, rightClick, standAt } from './world.js';

// A region that is not a box. An L-shaped plot leaves a corner of its bounding box out; what
// stands in that corner is not in the plot, whatever the box says.

/** The L: all of x-5..x+5 for z-5..z, and only x-5..x for z..z+5. The corner x+1..x+5, z+1..z+5 is out. */
function lShape(x: number, z: number): Array<[number, number]> {
    return [[x - 5, z - 5], [x + 5, z - 5], [x + 5, z], [x, z], [x, z + 5], [x - 5, z + 5]];
}

/** A chest in the L, at x+2,z-2, and one in the corner it leaves out, at x+3,z+3. */
async function twoChests(server: any, owner: PlayerWrapper, x: number, z: number) {
    await lockedChest(server, owner, x + 2, z - 2);
    await lockedChest(server, owner, x + 3, z + 3);
    return { insideX: x + 2, insideZ: z - 2, cornerX: x + 3, cornerZ: z + 3 };
}

test('a member of an L-shaped region vaults a chest in it, and not one in the corner it leaves out',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z - 1);
        await standAt(server, member, x - 1, z - 1);
        const chests = await twoChests(server, owner, x, z);
        const name = `ell_${x}`;
        await definePolygon(player, name, lShape(x, z), -64, -50);
        player.chat(`/rg addmember ${name} ${member.username}`);
        await expect(player).toHaveReceivedMessage(`'${name}' updated with new members`);
        await ordinary(server, member, [MENU, 'vaultstorage.action.capture']);

        await captureMode(member);
        await rightClick(member, chests.cornerX, FLOOR, chests.cornerZ);
        await expect(member).toHaveReceivedMessage('You may not vault that');
        await expectBlock(server, chests.cornerX, FLOOR, chests.cornerZ, 'chest', 'corner-chest-left');

        await rightClick(member, chests.insideX, FLOOR, chests.insideZ);
        await expect(member).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, chests.insideX, FLOOR, chests.insideZ, 'air', 'chest-in-the-ell-vaulted');
    });

test('a scan of an L-shaped region lists what is in it, and not what is only in its bounding box',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x - 1, z - 1);
        await standAt(server, owner, x, z - 1);
        const chests = await twoChests(server, owner, x, z);
        const name = `ell_${x}`;
        await definePolygon(player, name, lShape(x, z), -64, -50);
        const dialogs = new Dialogs(player);

        player.chat('/vault admin');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Scan Region');
        (await dialogTitled(dialogs, 'Vault Scan')).click('Here');
        let results;
        for (let attempt = 0; attempt < 6; attempt++) {
            results = await dialogs.next();
            if (results.title === 'Vault Scan' && results.body.includes('results')) {
                break;
            }
        }

        expect(results!.body).toContain(`Region ${name}`);
        expect(results!.body).toContain('(1 results)');
        expect(results!.body).toContain(`(${chests.insideX}, ${FLOOR}, ${chests.insideZ})`);
        expect(results!.body).not.toContain(`(${chests.cornerX}, ${FLOOR}, ${chests.cornerZ})`);
    });

test('"Here" in the corner an L-shaped region leaves out finds no region',
    async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x - 1, z - 1);
        await definePolygon(player, `ell_${x}`, lShape(x, z), -64, -50);
        await standAt(server, player, x + 3, z + 3);
        const dialogs = new Dialogs(player);

        player.chat('/vault admin');
        (await dialogTitled(dialogs, 'Vault Storage')).click('Scan Region');
        (await dialogTitled(dialogs, 'Vault Scan')).click('Here');

        await expect(player).toHaveReceivedMessage('You are not standing in a region.');
    });

test('buying an L-shaped region vaults what is in it and leaves what is in the corner',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await scene(server, player, owner, buyer);
        await standAt(server, owner, x, z - 1);
        const chests = await twoChests(server, owner, x, z);
        await definePolygon(player, region, lShape(x, z), -64, -50);
        await freehold(player, region, owner);

        await buy(server, buyer, region);

        await expect(buyer).toHaveReceivedMessage('cleared into their vault storage');
        await expectBlock(server, chests.insideX, FLOOR, chests.insideZ, 'air', 'swept-chest-in-the-ell');
        await sleep(SWEEP_HAS_RUN);
        await expectBlock(server, chests.cornerX, FLOOR, chests.cornerZ, 'chest', 'corner-chest-not-swept');
        await vaultsOf(owner, 1);
    });
