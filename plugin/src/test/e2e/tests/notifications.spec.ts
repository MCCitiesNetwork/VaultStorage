import { expect, sleep, test } from '@plugwright/runner';
import { queryDatabase } from './database.js';
import { operators } from './players.js';
import { buy, plotForSale } from './realty.js';
import { requirePlayerNotifications } from './shops.js';
import { captureMode, lockedChest } from './vaults.js';
import { FLOOR, freshSpot, rightClick, standAt } from './world.js';

// Telling a vault's owner, through PlayerNotifications itself: now if they are online, and
// when they next join if they are not.

/** How many notifications PlayerNotifications holds for the player. */
function stored(uuid: string): number {
    return Number(queryDatabase('player_notifications',
        `SELECT COUNT(*) FROM NotificationTarget WHERE playerUuid = UNHEX(REPLACE('${uuid}', '-', ''))`)[0] ?? '0');
}

test('an owner who is online is told at once that their chest was vaulted, and by whom',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requirePlayerNotifications();
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');

        await expect(owner).toHaveReceivedMessage('created a new vault for you');
        await expect(owner).toHaveReceivedMessage(player.username);
        // The staff member who vaulted it is not told anything of the kind.
        await expect(player).not.toHaveReceivedMessage('created a new vault for you', { timeout: 1000 });
    });

test('an owner who is offline is told when they come back',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requirePlayerNotifications();
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await operators(server, player, owner);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        await lockedChest(server, owner, x + 2, z);
        const name = owner.username;
        const uuid = owner.bot.player.uuid;
        owner.bot.quit();
        await sleep(1500);

        await captureMode(player);
        await rightClick(player, x + 2, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vault captured.');
        await sleep(1500);
        expect(stored(uuid)).toBeGreaterThan(0);

        const back = await createPlayer({ username: name });
        await expect(back).toHaveReceivedMessage('created a new vault for you', { timeout: 15000 });
    });

test('a previous occupant is told once how many of their containers a sweep vaulted, and where',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        requirePlayerNotifications();
        const [owner, buyer] = await Promise.all([createPlayer(), createPlayer()]);
        const { x, z, region } = await plotForSale(server, player, owner, buyer);
        await lockedChest(server, owner, x + 2, z);
        await lockedChest(server, owner, x + 3, z);

        await buy(server, buyer, region);

        await expect(owner).toHaveReceivedMessage('of your locked containers in', { timeout: 15000 });
        await expect(owner).toHaveReceivedMessage(region);
        await expect(owner).toHaveReceivedMessage('2 of your locked containers');
    });
