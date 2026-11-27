import { expect, sleep, waitUntil, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
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

/** Puts the player at a spot at once. Most tests need a place to stand, not a walk: see walkTo. */
export async function standAt(server: ServerWrapper, player: PlayerWrapper, x: number, z: number): Promise<void> {
    await server.execute(`minecraft:tp ${player.username} ${x}.5 ${FLOOR} ${z}.5`);
    // The chunk around the new position has to reach the bot before it can click a block in it.
    await sleep(1500);
}

let clickSequence = 1;

/**
 * Right-clicks the top face of a block. The packet is written directly: the server does not
 * need the player to face a block to accept a click on it, and a test can then click a block
 * behind another. mineflayer's activateBlock turns the bot first; movement.spec.ts uses it.
 */
export async function rightClick(player: PlayerWrapper, x: number, y: number, z: number): Promise<void> {
    // A chunk can reach a bot a few seconds after the teleport when several bots have just joined.
    await waitUntil(() => player.bot.blockAt(new Vec3(x, y, z)) !== null,
        { timeout: 10000, message: `${player.username} has no block loaded at ${x},${y},${z}` });
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

/**
 * Left-clicks a block: starts to dig it and stops at once, which is what a single click sends.
 * The block is not broken.
 */
export async function leftClick(player: PlayerWrapper, x: number, y: number, z: number): Promise<void> {
    const client = (player.bot as any)._client;
    client.write('block_dig', { status: 0, location: { x, y, z }, face: 1, sequence: clickSequence++ });
    client.write('block_dig', { status: 1, location: { x, y, z }, face: 1, sequence: clickSequence++ });
    await sleep(100);
}

/** Right-clicks a face of a block: 0 bottom, 1 top, 2 north, 3 south, 4 west, 5 east. */
export async function rightClickFace(player: PlayerWrapper, x: number, y: number, z: number, face: number): Promise<void> {
    await waitUntil(() => player.bot.blockAt(new Vec3(x, y, z)) !== null,
        { timeout: 10000, message: `${player.username} has no block loaded at ${x},${y},${z}` });
    (player.bot as any)._client.write('block_place', {
        hand: 0, location: { x, y, z }, direction: face,
        cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5,
        insideBlock: false, worldBorderHit: false, sequence: clickSequence++,
    });
    await sleep(100);
}

/** Asserts through the console that a block has this state, such as `chest[facing=east]`. */
export async function expectBlockState(server: ServerWrapper, x: number, y: number, z: number, state: string, marker: string): Promise<void> {
    await server.execute(`execute if block ${x} ${y} ${z} minecraft:${state} run say ${marker}`);
    await expect(server).toHaveReceivedMessage(marker);
}

/** Asserts through the console that a container slot holds this many of the given item. */
export async function expectItemCount(server: ServerWrapper, x: number, y: number, z: number, slot: number, item: string, count: number, marker: string): Promise<void> {
    await server.execute(`execute if items block ${x} ${y} ${z} container.${slot} minecraft:${item}[minecraft:count=${count}] run say ${marker}`);
    await expect(server).toHaveReceivedMessage(marker);
}

/** The nether, where the test world is not flat: specs build a platform to work on. */
export const NETHER = 'minecraft:the_nether';
export const NETHER_FLOOR = 100;

/** A stone platform with air above it in the nether, kept loaded, around a spot. */
export async function netherPlatform(server: ServerWrapper, x: number, z: number): Promise<void> {
    await server.execute(`execute in ${NETHER} run forceload add ${x - 8} ${z - 8} ${x + 8} ${z + 8}`);
    await server.execute(`execute in ${NETHER} run fill ${x - 8} ${NETHER_FLOOR} ${z - 8} ${x + 8} ${NETHER_FLOOR + 4} ${z + 8} minecraft:air`);
    await server.execute(`execute in ${NETHER} run fill ${x - 8} ${NETHER_FLOOR - 1} ${z - 8} ${x + 8} ${NETHER_FLOOR - 1} ${z + 8} minecraft:stone`);
}

/** Puts the player on the nether platform. */
export async function standInNether(server: ServerWrapper, player: PlayerWrapper, x: number, z: number): Promise<void> {
    await server.execute(`execute in ${NETHER} run minecraft:tp ${player.username} ${x}.5 ${NETHER_FLOOR} ${z}.5`);
    // A change of dimension takes longer to reach the bot than a move within one.
    await sleep(3000);
}

/** Asserts through the console that a block in the nether is of the given type. */
export async function expectNetherBlock(server: ServerWrapper, x: number, y: number, z: number, type: string, marker: string): Promise<void> {
    await server.execute(`execute in ${NETHER} if block ${x} ${y} ${z} minecraft:${type} run say ${marker}`);
    await expect(server).toHaveReceivedMessage(marker);
}


/** Where the server has the player, which is where the plugin sees them. */
export async function serverPosition(server: ServerWrapper, player: PlayerWrapper): Promise<{ x: number; y: number; z: number }> {
    const answer = await server.execute(`minecraft:data get entity ${player.username} Pos`);
    const numbers = answer.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
    if (numbers === null) {
        throw new Error(`No position for ${player.username}: ${answer}`);
    }
    return { x: Number(numbers[1]), y: Number(numbers[2]), z: Number(numbers[3]) };
}

/**
 * Walks the player to the middle of a block's column, in a straight line over flat ground, and
 * checks the server followed: a walk the server refused would leave the plugin seeing the
 * player where they started.
 */
export async function walkTo(server: ServerWrapper, player: PlayerWrapper, x: number, z: number): Promise<void> {
    const bot = player.bot as any;
    const target = () => new Vec3(x + 0.5, bot.entity.position.y + 1.62, z + 0.5);
    const remaining = () => Math.hypot(bot.entity.position.x - (x + 0.5), bot.entity.position.z - (z + 0.5));
    // Aimed again every tick, so a step that drifts is corrected instead of circling the target.
    const aim = () => {
        if (remaining() > 0.3) {
            void bot.lookAt(target(), true);
        } else {
            bot.setControlState('forward', false);
        }
    };
    await bot.lookAt(target(), true);
    bot.on('physicsTick', aim);
    bot.setControlState('forward', true);
    try {
        await waitUntil(() => remaining() <= 0.3,
            { timeout: 20000, message: `${player.username} did not reach ${x},${z}` });
    } finally {
        bot.removeListener('physicsTick', aim);
        bot.setControlState('forward', false);
    }
    // The last position packets reach the server within a few ticks.
    await sleep(500);
    const at = await serverPosition(server, player);
    expect(Math.hypot(at.x - (x + 0.5), at.z - (z + 0.5))).toBeLessThan(1);
}

/** Holds or lets go of the sneak key. */
export async function sneak(player: PlayerWrapper, held: boolean): Promise<void> {
    (player.bot as any).setControlState('sneak', held);
    await sleep(200);
}
