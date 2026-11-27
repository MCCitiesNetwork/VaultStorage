import { expect, sleep, test } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';

// The plugin enables under paper-plugin.yml and /vault is registered through Paper's command API.
// The menu opening is the proof that it enabled: a plugin that failed to start registers nothing.

test('/vault opens the capture menu for an operator', async ({ player, server }) => {
    await server.execute(`op ${player.username}`);
    const dialogs = new Dialogs(player);
    player.chat('/vault');
    const menu = await dialogs.next();
    expect(menu.title).toBe('Vault Storage');
    menu.button('Start Capture');
    menu.button('Scan Region');
    menu.button('Browse Vaults');
});

test('/vault is refused without the user permission', async ({ player }) => {
    player.chat('/vault');
    // A command whose permission the sender lacks is not sent to their client at all.
    await expect(player).toHaveReceivedMessage('Unknown or incomplete command');
});

test('vaultstorage.admin alone gives a player who is not an operator the staff commands', async ({ player, server }) => {
    await server.execute(`deop ${player.username}`);
    await server.execute(`lp user ${player.username} permission set vaultstorage.admin true`);
    await sleep(700);
    const dialogs = new Dialogs(player);
    player.chat('/vault admin');
    expect((await dialogs.next()).title).toBe('Vault Storage');
    player.chat('/vault reload');
    await expect(player).toHaveReceivedMessage('VaultStorage reloaded.');
});

test('a player with only the menu permission is not offered the staff commands', async ({ player, server }) => {
    await server.execute(`deop ${player.username}`);
    await server.execute(`lp user ${player.username} permission set vaultstorage.command.vault true`);
    await sleep(700);
    const dialogs = new Dialogs(player);
    player.chat('/vault');
    expect((await dialogs.next()).title).toBe('Vault Storage');
    for (const refused of ['/vault reload']) {
        player.chat(refused);
        // The root is known to this player, so the client's answer names the argument, not the command.
        await expect(player).toHaveReceivedMessage('Incorrect argument for command');
        await sleep(300);
    }
});
