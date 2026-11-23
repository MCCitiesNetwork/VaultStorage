import { expect, test } from '@plugwright/runner';
import { query, seedVaults } from './database.js';
import { Dialogs } from './dialogs.js';
import { adminVaultActions, dialogTitled, openWindow } from './vaults.js';

// What only an admin can do to someone else's vault.

test('an admin deletes a vault from its action menu', async ({ player, createPlayer, server }) => {
    const owner = await createPlayer();
    await server.execute(`op ${player.username}`);
    const ownerUuid = owner.bot.player.uuid;
    seedVaults(ownerUuid, 1);
    const dialogs = new Dialogs(player);

    const actions = await adminVaultActions(player, dialogs, owner.username);
    // The action menu says which vault it is: what it was and where it stood.
    expect(actions.body).toContain(`${owner.username}'s Vault #1`);
    expect(actions.body).toContain('Chest at');

    // Delete asks first, and Cancel keeps the vault.
    actions.click('Delete Vault');
    const confirm = await dialogTitled(dialogs, 'Delete this vault?');
    expect(confirm.body).toContain('cannot be undone');
    confirm.click('Cancel');
    const again = await dialogTitled(dialogs, 'Vault Action');
    expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${ownerUuid}'`)).toEqual(['1']);

    again.click('Delete Vault');
    (await dialogTitled(dialogs, 'Delete this vault?')).click('Delete');
    await expect(player).toHaveReceivedMessage('Vault deleted.');
    expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${ownerUuid}'`)).toEqual(['0']);
});

test('an admin transfers a vault to another player by name', async ({ player, createPlayer, server }) => {
    const [from, to] = await Promise.all([createPlayer(), createPlayer()]);
    await server.execute(`op ${player.username}`);
    seedVaults(from.bot.player.uuid, 1);
    const [vault] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${from.bot.player.uuid}'`);

    player.chat(`/vault transfer ${vault} ${to.username}`);
    await expect(player).toHaveReceivedMessage(`transferred from ${from.bot.player.uuid} to ${to.bot.player.uuid}`);
    expect(query(`SELECT ownerUuid FROM Vault WHERE uuid = '${vault}'`)).toEqual([to.bot.player.uuid]);

    player.chat(`/vault transfer 00000000-0000-0000-0000-00000000dead ${to.username}`);
    await expect(player).toHaveReceivedMessage('Vault not found');
});

test('a vault is opened by id by its owner', async ({ player, server }) => {
    await server.execute(`op ${player.username}`);
    seedVaults(player.bot.player.uuid, 1);
    const [vault] = query(`SELECT uuid FROM Vault WHERE ownerUuid = '${player.bot.player.uuid}'`);

    player.chat(`/vault open ${vault} view`);
    await openWindow(player);

    player.chat('/vault open 00000000-0000-0000-0000-00000000dead view');
    await expect(player).toHaveReceivedMessage('Vault not found.');
});
