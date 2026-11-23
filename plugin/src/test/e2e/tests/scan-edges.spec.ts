import { expect, sleep, test, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { Dialogs, type Dialog } from './dialogs.js';
import { MENU, operators, ordinary } from './players.js';
import { dialogTitled, lock, lockedChest } from './vaults.js';
import { FLOOR, freshSpot, standAt } from './world.js';

// Scans beyond one chest in one region: who may scan what, the cooldown, searching, paging and
// the filters.

interface Bounds { minX: number; maxX: number; minY: number; maxY: number; minZ: number; maxZ: number }

function around(x: number, z: number): Bounds {
    return { minX: x - 5, maxX: x + 5, minY: -64, maxY: -50, minZ: z - 5, maxZ: z + 5 };
}

async function region(staff: PlayerWrapper, name: string, bounds: Bounds, members: PlayerWrapper[] = []): Promise<string> {
    staff.chat(`//pos1 ${bounds.minX},${bounds.minY},${bounds.minZ}`);
    staff.chat(`//pos2 ${bounds.maxX},${bounds.maxY},${bounds.maxZ}`);
    await sleep(300);
    staff.chat(`/rg define ${name}`);
    await expect(staff).toHaveReceivedMessage(name);
    if (members.length > 0) {
        staff.chat(`/rg addmember ${name} ${members.map((member) => member.username).join(' ')}`);
        await expect(staff).toHaveReceivedMessage(`'${name}' updated with new members`);
    }
    return name;
}

async function scanResults(dialogs: Dialogs): Promise<Dialog> {
    for (let attempt = 0; attempt < 6; attempt++) {
        const dialog = await dialogs.next();
        if (dialog.title === 'Vault Scan' && dialog.body.includes('results')) {
            return dialog;
        }
    }
    throw new Error('No scan results were shown');
}

/** Opens the scan menu's first screen. */
async function scanMenu(player: PlayerWrapper, dialogs: Dialogs, admin: boolean): Promise<Dialog> {
    player.chat(admin ? '/vault admin' : '/vault');
    (await dialogTitled(dialogs, 'Vault Storage')).click('Scan Region');
    return dialogTitled(dialogs, 'Vault Scan');
}

async function scene(server: ServerWrapper, staff: PlayerWrapper, owner: PlayerWrapper) {
    const { x, z } = freshSpot();
    await operators(server, staff, owner);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    return { x, z };
}

test('"Here" outside every region says there is no region',
    async ({ player, server }) => {
        const { x, z } = freshSpot();
        await operators(server, player);
        await standAt(server, player, x, z);
        (await scanMenu(player, new Dialogs(player), true)).click('Here');
        await expect(player).toHaveReceivedMessage('You are not standing in a region.');
    });

test('a player may scan a region they belong to, and not one they do not',
    async ({ player, createPlayer, server }) => {
        const [owner, member, stranger] = await Promise.all([createPlayer(), createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await standAt(server, member, x + 1, z + 1);
        await standAt(server, stranger, x - 1, z + 1);
        await lockedChest(server, owner, x + 2, z);
        const name = await region(player, `scan_${x}`, around(x, z), [member]);
        await ordinary(server, member, [MENU]);
        await ordinary(server, stranger, [MENU]);

        (await scanMenu(stranger, new Dialogs(stranger), false)).click('Here');
        await expect(stranger).toHaveReceivedMessage('You can only scan a region you own or are a member of.');

        const memberDialogs = new Dialogs(member);
        (await scanMenu(member, memberDialogs, false)).click('Here');
        const results = await scanResults(memberDialogs);
        expect(results.body).toContain(`Region ${name}`);
        expect(results.body).toContain('(1 results)');
        expect(results.body).toContain('vaultable: yes');
    });

test('a scan lists only what the player may vault: a co-member\'s chest is left out',
    async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await standAt(server, member, x + 1, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await region(player, `scan_${x}`, around(x, z), [member, owner]);
        await ordinary(server, member, [MENU, 'vaultstorage.action.capture']);
        const dialogs = new Dialogs(member);

        (await scanMenu(member, dialogs, false)).click('Here');
        const results = await scanResults(dialogs);

        expect(results.body).toContain('(0 results)');
        expect(results.buttons.some((button) => button.label.includes('(vault)'))).toBe(false);
    });

test('a scan result cannot be vaulted by a player without the capture permission',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await standAt(server, member, x + 1, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await region(player, `scan_${x}`, around(x, z), [member]);
        await ordinary(server, member, [MENU]);
        const dialogs = new Dialogs(member);
        (await scanMenu(member, dialogs, false)).click('Here');
        const results = await scanResults(dialogs);
        expect(results.body).toContain('(1 results)');

        results.click('(vault) - 1');

        await expect(member).toHaveReceivedMessage('You are not allowed to vault containers.');
        const { expectBlock } = await import('./world.js');
        await expectBlock(server, x + 2, FLOOR, z, 'chest', 'scan-button-without-permission-left-it');
    });

test('a member with the capture permission vaults a non-member\'s chest from the scan',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await standAt(server, member, x + 1, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await region(player, `scan_${x}`, around(x, z), [member]);
        await ordinary(server, member, [MENU, 'vaultstorage.action.capture']);
        const dialogs = new Dialogs(member);
        (await scanMenu(member, dialogs, false)).click('Here');

        (await scanResults(dialogs)).click('(vault) - 1');

        await expect(member).toHaveReceivedMessage('Vaulted.');
        const { expectBlock } = await import('./world.js');
        await expectBlock(server, x + 2, FLOOR, z, 'air', 'member-vaulted-from-scan');
    });

test('a second scan straight after the first is refused until the cooldown has passed',
    async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await standAt(server, member, x + 1, z + 1);
        await region(player, `scan_${x}`, around(x, z), [member]);
        await ordinary(server, member, [MENU]);
        const dialogs = new Dialogs(member);

        (await scanMenu(member, dialogs, false)).click('Here');
        await scanResults(dialogs);
        (await scanMenu(member, dialogs, false)).click('Here');

        await expect(member).toHaveReceivedMessage('You can scan again in');
    });

test('staff are not held by the scan cooldown',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await region(player, `scan_${x}`, around(x, z));
        const dialogs = new Dialogs(player);

        (await scanMenu(player, dialogs, true)).click('Here');
        await scanResults(dialogs);
        (await scanMenu(player, dialogs, true)).click('Here');
        expect((await scanResults(dialogs)).body).toContain('(0 results)');
    });

test('a region with nothing locked in it says so',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await region(player, `scan_${x}`, around(x, z));
        const dialogs = new Dialogs(player);

        (await scanMenu(player, dialogs, true)).click('Here');
        const results = await scanResults(dialogs);

        expect(results.body).toContain('(0 results)');
        expect(results.body).toContain('No protected blocks found in this region.');
    });

test('regions are found by part of their name, and a name nothing matches says so',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);
        const name = await region(player, `findme_${x}`, around(x, z));
        const dialogs = new Dialogs(player);

        (await scanMenu(player, dialogs, true)).click('Search', { QUERY: `ndme_${x}` });
        const found = await dialogTitled(dialogs, 'Regions');
        found.click(name);
        expect((await scanResults(dialogs)).body).toContain(`Region ${name}`);

        (await scanMenu(player, dialogs, true)).click('Search', { QUERY: 'no_region_has_this_name' });
        expect((await dialogTitled(dialogs, 'Regions')).body).toContain('No regions match.');
    });

test('the region list shows an ordinary player only the regions they belong to',
    async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await standAt(server, member, x + 1, z + 1);
        const mine = await region(player, `mine_${x}`, around(x, z), [member]);
        const theirs = await region(player, `theirs_${x}`, { ...around(x, z), minX: x + 20, maxX: x + 30 });
        await ordinary(server, member, [MENU]);
        const dialogs = new Dialogs(member);

        (await scanMenu(member, dialogs, false)).click('Search', { QUERY: `_${x}` });
        const list = await dialogTitled(dialogs, 'Regions');

        expect(list.buttons.some((button) => button.label === mine)).toBe(true);
        expect(list.buttons.some((button) => button.label === theirs)).toBe(false);
    });

test('"Here" where two regions overlap offers both to choose from',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        const west = await region(player, `west_${x}`, { ...around(x, z), maxX: x + 3 });
        const east = await region(player, `east_${x}`, { ...around(x, z), minX: x - 1, maxX: x + 7 });
        const dialogs = new Dialogs(player);

        (await scanMenu(player, dialogs, true)).click('Here');
        const choice = await dialogTitled(dialogs, 'Regions');

        choice.button(west);
        choice.button(east);
        expect(choice.body).toContain('2 regions');
    });

test('scan results run to further pages, five to a page, in a fixed order',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        for (let i = 0; i < 7; i++) {
            await server.execute(`setblock ${x - 3 + i} ${FLOOR} ${z + 3} minecraft:chest`);
            await server.execute(`minecraft:item replace block ${x - 3 + i} ${FLOOR} ${z + 3} container.0 with minecraft:diamond 1`);
            await lock(owner, x - 3 + i, z + 3);
        }
        await region(player, `scan_${x}`, around(x, z));
        const dialogs = new Dialogs(player);

        (await scanMenu(player, dialogs, true)).click('Here');
        const first = await scanResults(dialogs);
        expect(first.body).toContain('(7 results)');
        expect(first.buttons.filter((button) => button.label.includes('(vault)')).length).toBe(5);
        expect(first.body).toContain('Page 1 / 2');

        first.click('Next');
        const second = await scanResults(dialogs);
        expect(second.buttons.filter((button) => button.label.includes('(vault)')).length).toBe(2);
        expect(second.body).toContain('Page 2 / 2');
        // Nothing on the second page was on the first.
        for (const line of second.body.split('\n').filter((entry) => entry.includes('owner:'))) {
            expect(first.body).not.toContain(line.replace(/^- \d+ /, ''));
        }
        second.click('Prev');
        expect((await scanResults(dialogs)).body).toContain('Page 1 / 2');
    });

test('the filter narrows the results to containers, to other blocks, and to entities',
    async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = await scene(server, player, owner);
        await lockedChest(server, owner, x + 2, z);
        await server.execute(`setblock ${x + 3} ${FLOOR} ${z} minecraft:anvil`);
        await lock(owner, x + 3, z);
        await region(player, `scan_${x}`, around(x, z));
        const dialogs = new Dialogs(player);

        (await scanMenu(player, dialogs, true)).click('Here');
        const all = await scanResults(dialogs);
        expect(all.body).toContain('(2 results)');

        all.click('Filter: Block');
        const blocksOnly = await scanResults(dialogs);
        expect(blocksOnly.body).toContain('(1 results)');
        expect(blocksOnly.body).toContain('| block |');

        blocksOnly.click('Filter: Non-Container');
        const containers = await scanResults(dialogs);
        expect(containers.body).toContain('(1 results)');
        expect(containers.body).toContain('| container |');

        containers.click('Filter: Container');
        const entities = await scanResults(dialogs);
        expect(entities.body).toContain('No protected entities found in this region.');
    });

test('the teleport button takes staff to a result, and is not offered without its permission',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, member] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await standAt(server, member, x + 1, z + 1);
        await lockedChest(server, owner, x + 4, z + 4);
        await region(player, `scan_${x}`, around(x, z), [member]);
        await ordinary(server, member, [MENU]);

        const memberDialogs = new Dialogs(member);
        (await scanMenu(member, memberDialogs, false)).click('Here');
        const forMember = await scanResults(memberDialogs);
        expect(forMember.buttons.some((button) => button.label.includes('teleport'))).toBe(false);

        const dialogs = new Dialogs(player);
        (await scanMenu(player, dialogs, true)).click('Here');
        (await scanResults(dialogs)).click('(teleport)');
        await sleep(800);
        // On top of the chest: a chest cannot be stood in. Half a block down, because a chest
        // is lower than a full block and the player settles onto its lid.
        await server.execute(`execute as ${player.username} at @s if block ~ ~-0.5 ~ minecraft:chest run say teleported-onto-the-chest`);
        await expect(server).toHaveReceivedMessage('teleported-onto-the-chest');
    });

test('a chest vaulted by someone else since the scan is no longer vaulted from the stale list',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const [owner, second] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z } = await scene(server, player, owner);
        await operators(server, second);
        await standAt(server, second, x + 1, z + 1);
        await lockedChest(server, owner, x + 2, z);
        await region(player, `scan_${x}`, around(x, z));
        const dialogs = new Dialogs(player);
        (await scanMenu(player, dialogs, true)).click('Here');
        const results = await scanResults(dialogs);

        // Someone else vaults it by hand while the list is on screen.
        second.chat('/vault capture');
        await expect(second).toHaveReceivedMessage('Capture mode is on');
        const { rightClick } = await import('./world.js');
        await rightClick(second, x + 2, FLOOR, z);
        await expect(second).toHaveReceivedMessage('Vaulted.');

        results.click('(vault) - 1');
        await expect(player).toHaveReceivedMessage('You may not vault that');
        const after = await scanResults(dialogs);
        expect(after.body).toContain('(0 results)');
    });
