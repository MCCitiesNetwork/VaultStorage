import { expect, sleep, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { queryDatabase } from './database.js';
import { FLOOR, rightClick } from './world.js';

// ChestShop shops for the specs that need one. ChestShop and Treasury are installed by
// installE2eEconomy in plugin/build.gradle.kts.

/** The jar names in the test server's plugins folder. */
function installedPlugins(): string[] {
    let folder = process.cwd();
    for (let depth = 0; depth < 6; depth++) {
        for (const plugins of [
            path.join(folder, 'generated/local/run/plugins'),
            path.join(folder, 'src/test/e2e/generated/local/run/plugins'),
            path.join(folder, 'plugin/src/test/e2e/generated/local/run/plugins'),
        ]) {
            if (fs.existsSync(plugins)) {
                return fs.readdirSync(plugins);
            }
        }
        folder = path.dirname(folder);
    }
    return [];
}

/**
 * Fails the test when ChestShop is not on the test server. A spec that needs a shop must not
 * pass without having run.
 */
export function requireChestShop(): void {
    if (!installedPlugins().some((name) => /^chestshop-.*\.jar$/.test(name))) {
        throw new Error('ChestShop is not on the test server. installE2eEconomy in plugin/build.gradle.kts '
            + 'takes it from a hibernia-economy checkout or from GitHub; see its warning in the Gradle output.');
    }
}

/** Fails the test when Realty, or the economy it needs, is not on the test server. */
export function requireRealty(): void {
    requireChestShop();
    if (!installedPlugins().some((name) => /^realty-paper-.*\.jar$/.test(name))) {
        throw new Error('Realty is not on the test server. installE2eRealty in plugin/build.gradle.kts '
            + 'takes it from GitHub Actions or from -PrealtyJar; see its warning in the Gradle output.');
    }
}

/**
 * A chest with the owner's shop sign standing on it, locked by the owner with Bolt or not.
 *
 * The sign is placed on a stone block and the block is turned into the chest afterwards: a
 * click on a chest opens it unless the player sneaks, and these bots send no input packets.
 * The sign text is sent the way a client sends it, so ChestShop registers the shop.
 */
export async function shop(server: ServerWrapper, owner: PlayerWrapper, x: number, z: number, withItems: boolean, locked: boolean): Promise<void> {
    await server.execute(`setblock ${x} ${FLOOR} ${z} minecraft:stone`);
    await server.execute(`minecraft:item replace entity ${owner.username} weapon.mainhand with minecraft:oak_sign`);
    await sleep(300);
    await rightClick(owner, x, FLOOR, z);
    await sleep(400);
    await server.execute(`setblock ${x} ${FLOOR} ${z} minecraft:chest`);
    if (withItems) {
        await server.execute(`minecraft:item replace block ${x} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
    }
    (owner.bot as any)._client.write('update_sign', {
        location: { x, y: FLOOR + 1, z }, isFrontText: true,
        text1: owner.username, text2: '1', text3: 'B 5', text4: 'DIAMOND',
    });
    await expect(owner).toHaveReceivedMessage('Shop successfully created');
    if (!locked) {
        return;
    }
    owner.chat('/lock');
    await sleep(500);
    await rightClick(owner, x, FLOOR, z);
    await sleep(500);
}

/** Whether ChestShop's own registry still lists a shop at the sign as open for trade. */
export async function shopIsActive(x: number, z: number): Promise<boolean> {
    // The registry is written off the main thread, a moment after the sign goes.
    await sleep(1000);
    const rows = queryDatabase('treasury',
        `SELECT active FROM chestshop_shop WHERE sign_x = ${x} AND sign_y = ${FLOOR + 1} AND sign_z = ${z}`);
    expect(rows).toHaveLength(1);
    return rows[0] === '1';
}

