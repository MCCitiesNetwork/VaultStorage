import { expect, sleep, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { Vec3 } from 'vec3';

// The flat test world: grass at y -61, so a player stands and blocks are placed at y -60.
export const GROUND = -61;
export const FLOOR = -60;

// Every spec file is loaded into one process, so this counter hands each test its own spot.
let nextSpot = 1000;

/** A fresh spot, 100 blocks from any other test's. */
export function freshSpot(): { x: number; z: number } {
    const spot = nextSpot;
    nextSpot += 100;
    return { x: spot, z: spot };
}

/** Puts the player at a spot. Bots do not send movement, so they are placed with the console. */
export async function standAt(server: ServerWrapper, player: PlayerWrapper, x: number, z: number): Promise<void> {
    await server.execute(`minecraft:tp ${player.username} ${x}.5 ${FLOOR} ${z}.5`);
    // The chunk around the new position has to reach the bot before it can click a block in it.
    await sleep(1500);
}

let clickSequence = 1;

/**
 * Right-clicks the top face of a block. The packet is written directly instead of through
 * mineflayer's activateBlock, which first turns the bot to face the block: through ViaBackwards
 * that look packet now and then gets the bot kicked ("Invalid move player packet received"). The
 * server does not need the player to face a block to accept a click on it.
 */
export async function rightClick(player: PlayerWrapper, x: number, y: number, z: number): Promise<void> {
    if (!player.bot.blockAt(new Vec3(x, y, z))) {
        throw new Error(`${player.username} has no block loaded at ${x},${y},${z}`);
    }
    (player.bot as any)._client.write('block_place', {
        hand: 0, location: { x, y, z }, direction: 1,
        cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5,
        insideBlock: false, worldBorderHit: false, sequence: clickSequence++,
    });
    // One tick, so the server has handled the click before the test goes on.
    await sleep(100);
}

/** Asserts through the console that a block is of the given type. */
export async function expectBlock(server: ServerWrapper, x: number, y: number, z: number, type: string, marker: string): Promise<void> {
    await server.execute(`execute if block ${x} ${y} ${z} minecraft:${type} run say ${marker}`);
    await expect(server).toHaveReceivedMessage(marker);
}

/** Asserts through the console that a container slot holds the given item. */
export async function expectItem(server: ServerWrapper, x: number, y: number, z: number, slot: number, item: string, marker: string): Promise<void> {
    await server.execute(`execute if items block ${x} ${y} ${z} container.${slot} minecraft:${item} run say ${marker}`);
    await expect(server).toHaveReceivedMessage(marker);
}
