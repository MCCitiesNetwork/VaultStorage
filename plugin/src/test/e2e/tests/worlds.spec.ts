import { expect, sleep, test } from '@plugwright/runner';
import { seedVaults } from './database.js';
import { Dialogs } from './dialogs.js';
import { MENU, operators, ordinary } from './players.js';
import { around, defineRegion } from './realty.js';
import { captureMode, dialogTitled, expectVaults, placeFirstVault, vaultCount } from './vaults.js';
import {
    expectBlock, expectItem, expectNetherBlock, FLOOR, freshSpot, NETHER, NETHER_FLOOR, netherPlatform, rightClick,
    standAt, standInNether,
} from './world.js';

// More than one world. A vault remembers the world it came from, regions and locks belong to
// one world each, and a vault can be put down in a different world from the one it left.

/** A chest on the nether platform with diamonds in it, locked by the owner standing next to it. */
async function netherChest(server: any, owner: any, x: number, z: number): Promise<void> {
    await server.execute(`execute in ${NETHER} run setblock ${x} ${NETHER_FLOOR} ${z} minecraft:chest`);
    await server.execute(`execute in ${NETHER} run minecraft:item replace block ${x} ${NETHER_FLOOR} ${z} container.0 with minecraft:diamond 3`);
    owner.chat('/lock');
    await sleep(500);
    await rightClick(owner, x, NETHER_FLOOR, z);
    await sleep(500);
}

test('a chest vaulted in the nether is listed with that world, and can be placed in the overworld',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await netherPlatform(server, x, z);
        await standInNether(server, player, x, z);
        await standInNether(server, owner, x, z + 1);
        await netherChest(server, owner, x + 2, z);

        await captureMode(player);
        await rightClick(player, x + 2, NETHER_FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await expectNetherBlock(server, x + 2, NETHER_FLOOR, z, 'air', 'nether-chest-gone');
        await expectVaults(owner, 1);

        const dialogs = new Dialogs(owner);
        owner.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        const label = (await dialogTitled(dialogs, 'Browse Vaults')).button('Vault #').label;
        expect(label).toContain(`at world_nether ${x + 2}, ${NETHER_FLOOR}, ${z}`);

        await standAt(server, owner, x, z + 1);
        await sleep(1500);
        await placeFirstVault(owner, dialogs, x + 2, z + 2);
        await expect(owner).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x + 2, FLOOR, z + 2, 'chest', 'nether-vault-placed-in-overworld');
        await expectItem(server, x + 2, FLOOR, z + 2, 0, 'diamond', 'nether-vault-kept-its-items');
    });

test('a region in the overworld gives its members no say over a chest at the same place in the nether',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        const name = `over_${x}`;
        await defineRegion(player, name, { ...around(x, z), minY: -64, maxY: 200 });
        player.chat(`/rg addmember ${name} ${member.username}`);
        await expect(player).toHaveReceivedMessage(`'${name}' updated with new members`);
        await netherPlatform(server, x, z);
        await standInNether(server, owner, x, z + 1);
        await standInNether(server, member, x + 1, z + 1);
        await netherChest(server, owner, x + 2, z);
        await ordinary(server, member, [MENU, 'vaultstorage.action.capture']);

        await captureMode(member);
        await rightClick(member, x + 2, NETHER_FLOOR, z);

        await expect(member).toHaveReceivedMessage('Not allowed');
        await expectNetherBlock(server, x + 2, NETHER_FLOOR, z, 'chest', 'nether-chest-outside-the-overworld-region');
        expect(vaultCount(owner)).toBe(0);
    });

test('a vault from a world the server no longer has is listed by that world\'s id and can still be placed',
    { requires: { consoleOutput: 'full' } }, async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x, z);
        // Seeded vaults belong to a world this server never had.
        seedVaults(player.bot.player.uuid, 1);
        const dialogs = new Dialogs(player);

        player.chat('/vault');
        (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
        expect((await dialogTitled(dialogs, 'Browse Vaults')).button('Vault #').label).toContain('at 99999999 ');

        await placeFirstVault(player, dialogs, x + 2, z + 2);
        await expect(player).toHaveReceivedMessage('Vault placed.');
        await expectBlock(server, x + 2, FLOOR, z + 2, 'chest', 'vault-from-a-gone-world-placed');
    });
