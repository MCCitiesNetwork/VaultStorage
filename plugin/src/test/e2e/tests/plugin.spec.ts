import { expect, test } from '@plugwright/runner';
import { Dialogs } from './dialogs.js';

// The plugin enables under paper-plugin.yml and /vault is registered through Paper's command API.
// The menu opening is the proof that it enabled: a plugin that failed to start registers nothing.

test('/vault opens the capture menu for an operator', async ({ player, server }) => {
    await server.execute(`op ${player.username}`);
    const dialogs = new Dialogs(player);
    player.chat('/vault');
    const menu = await dialogs.next();
    expect(menu.title).toBe('Vault Capture');
    menu.button('Start Capture');
    menu.button('Scan Region');
    menu.button('Browse Vaults');
});

test('/vault is refused without the user permission', async ({ player }) => {
    player.chat('/vault');
    // A command whose permission the sender lacks is not sent to their client at all.
    await expect(player).toHaveReceivedMessage('Unknown or incomplete command');
});
