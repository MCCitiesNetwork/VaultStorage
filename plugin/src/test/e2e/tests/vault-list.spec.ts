import { expect, test } from '@plugwright/runner';
import { seedVaults } from './database.js';
import { Dialogs } from './dialogs.js';
import { dialogTitled } from './vaults.js';

// The vault list shows a page at a time, however many vaults there are.

test('an owner with more vaults than fit on a page pages through them', async ({ player, server }) => {
    await server.execute(`op ${player.username}`);
    seedVaults(player.bot.player.uuid, 45);
    const dialogs = new Dialogs(player);

    player.chat('/vault');
    (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
    const first = await dialogTitled(dialogs, 'Browse Vaults');
    expect(first.body).toContain('Page 1 / 3 (45 vaults)');
    first.button('Vault #1');
    first.button('Vault #20');
    expect(first.buttons.filter((button) => button.label.includes('Vault #'))).toHaveLength(20);
    expect(first.buttons.some((button) => button.label.includes('Prev'))).toBe(false);
    first.click('Next');

    const second = await dialogTitled(dialogs, 'Browse Vaults');
    expect(second.body).toContain('Page 2 / 3 (45 vaults)');
    second.button('Vault #21');
    second.click('Next');

    const third = await dialogTitled(dialogs, 'Browse Vaults');
    expect(third.body).toContain('Page 3 / 3 (45 vaults)');
    expect(third.buttons.filter((button) => button.label.includes('Vault #'))).toHaveLength(5);
    third.button('Vault #45');
    expect(third.buttons.some((button) => button.label.includes('Next'))).toBe(false);
    third.click('Prev');
    expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('Page 2 / 3');
});

test('an admin searches the list by owner name', async ({ player, createPlayer, server }) => {
    const owner = await createPlayer();
    await server.execute(`op ${player.username}`);
    seedVaults(owner.bot.player.uuid, 3);
    const dialogs = new Dialogs(player);

    player.chat('/vault admin');
    (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
    (await dialogTitled(dialogs, 'Browse Vaults')).click('Search', { QUERY: owner.username });
    const found = await dialogTitled(dialogs, 'Browse Vaults');
    expect(found.body).toContain('(3 vaults)');
    found.button(`${owner.username}'s Vault #1`);

    found.click('Search', { QUERY: 'nobody_by_this_name' });
    expect((await dialogTitled(dialogs, 'Browse Vaults')).body).toContain('No vaults found.');
});
