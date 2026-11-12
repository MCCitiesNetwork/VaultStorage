import { expect, sleep, waitUntil, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { Dialogs, type Dialog } from './dialogs.js';
import { FLOOR, freshSpot, rightClick, standAt } from './world.js';

// Shared steps for specs that need a vault to exist.

/** Reads dialogs until one has this title; the loading dialog comes in between. */
export async function dialogTitled(dialogs: Dialogs, title: string): Promise<Dialog> {
    for (let attempt = 0; attempt < 5; attempt++) {
        const dialog = await dialogs.next();
        if (dialog.title === title) {
            return dialog;
        }
    }
    throw new Error(`No dialog titled "${title}" was shown`);
}

/** A chest at the spot with a diamond stack in slot 0 and stone in slot 13, locked by its owner. */
export async function lockedChest(server: ServerWrapper, owner: PlayerWrapper, x: number, z: number): Promise<void> {
    await server.execute(`setblock ${x} ${FLOOR} ${z} minecraft:chest`);
    await server.execute(`minecraft:item replace block ${x} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
    await server.execute(`minecraft:item replace block ${x} ${FLOOR} ${z} container.13 with minecraft:stone 5`);
    // Bolt answers on the action bar, which a bot's chat log does not hold, so nothing is asserted
    // here. A chest that is not locked cannot be captured, so the capture below proves the lock.
    owner.chat('/lock');
    await sleep(500);
    await rightClick(owner, x, FLOOR, z);
    await sleep(500);
}


/**
 * Makes both players operators, stands them at a fresh spot, and has the staff member capture a
 * chest the owner locked: diamonds in slot 0, stone in slot 13. Returns the spot.
 */
export async function vaultFor(server: ServerWrapper, staff: PlayerWrapper, owner: PlayerWrapper): Promise<{ x: number; z: number }> {
    const { x, z } = freshSpot();
    await Promise.all([
        server.execute(`op ${staff.username}`),
        server.execute(`op ${owner.username}`),
    ]);
    await standAt(server, staff, x, z);
    await standAt(server, owner, x, z + 1);
    await lockedChest(server, owner, x + 2, z);
    staff.chat('/vault capture');
    await expect(staff).toHaveReceivedMessage('Capture session started');
    await rightClick(staff, x + 2, FLOOR, z);
    await expect(staff).toHaveReceivedMessage('Vault captured.');
    return { x, z };
}

/** Walks the owner's own menu to the action screen of their first vault. */
export async function ownVaultActions(player: PlayerWrapper, dialogs: Dialogs): Promise<Dialog> {
    player.chat('/vault');
    (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
    const list = await dialogTitled(dialogs, 'Browse Vaults');
    list.click(list.button('Vault #').label);
    return dialogTitled(dialogs, 'Vault Action');
}

/** Walks the admin menu to the action screen of the vault belonging to this owner. */
export async function adminVaultActions(admin: PlayerWrapper, dialogs: Dialogs, ownerName: string): Promise<Dialog> {
    admin.chat('/vault admin');
    (await dialogTitled(dialogs, 'Vault Capture')).click('Browse Vaults');
    const list = await dialogTitled(dialogs, 'Browse Vaults');
    list.click(list.button(`${ownerName}'s Vault #`).label);
    return dialogTitled(dialogs, 'Vault Action');
}

/** Waits for an inventory window to open on the bot and returns it. */
export async function openWindow(player: PlayerWrapper): Promise<any> {
    await waitUntil(() => (player.bot as any).currentWindow != null,
        { timeout: 5000, message: `${player.username} never saw an inventory open` });
    // The contents arrive just after the window itself.
    await sleep(300);
    return (player.bot as any).currentWindow;
}
