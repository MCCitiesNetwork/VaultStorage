import { expect, sleep, waitUntil, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import { query } from './database.js';
import { requireRealty } from './shops.js';
import { freshSpot, standAt } from './world.js';

// Regions changing hands through Realty itself, for the sweep specs. Realty replaces a
// region's WorldGuard owners and members with the new occupant before it fires the event the
// sweep starts from.

export interface Bounds { minX: number; maxX: number; minY: number; maxY: number; minZ: number; maxZ: number }

/** The whole height of the test world around a spot. */
export function around(x: number, z: number): Bounds {
    return { minX: x - 5, maxX: x + 5, minY: -64, maxY: -50, minZ: z - 5, maxZ: z + 5 };
}

/** A WorldGuard region with these bounds, defined by staff. */
export async function defineRegion(staff: PlayerWrapper, region: string, bounds: Bounds): Promise<void> {
    staff.chat(`//pos1 ${bounds.minX},${bounds.minY},${bounds.minZ}`);
    staff.chat(`//pos2 ${bounds.maxX},${bounds.maxY},${bounds.maxZ}`);
    await sleep(300);
    staff.chat(`/rg define ${region}`);
    await expect(staff).toHaveReceivedMessage(region);
}

/** Staff, the previous occupant and the newcomer at a fresh spot. Staff and the occupant are operators. */
export async function scene(server: ServerWrapper, staff: PlayerWrapper, occupant: PlayerWrapper, newcomer: PlayerWrapper) {
    requireRealty();
    const { x, z } = freshSpot();
    await Promise.all([
        server.execute(`op ${staff.username}`),
        server.execute(`op ${occupant.username}`),
    ]);
    await standAt(server, staff, x, z);
    await standAt(server, occupant, x, z + 1);
    await standAt(server, newcomer, x, z - 1);
    return { x, z, region: `sweep_${x}` };
}

/**
 * Registers the region with Realty as a freehold for sale at 1, held by the occupant. Staff is
 * its authority: Realty wants one, and the default one is a government account the test
 * server does not have.
 */
export async function freehold(staff: PlayerWrapper, region: string, occupant: PlayerWrapper): Promise<void> {
    staff.chat(`/realty register freehold ${region} --price 1 --titleholder ${occupant.username} --authority ${staff.username}`);
    await expect(staff).toHaveReceivedMessage('Freehold region registered');
}

/** Registers the region with Realty as a vacant leasehold at 1 a day, with staff as landlord. */
export async function leasehold(staff: PlayerWrapper, region: string): Promise<void> {
    staff.chat(`/realty register leasehold 1 1d -1 ${region} --landlord ${staff.username}`);
    await expect(staff).toHaveReceivedMessage('Rental region registered');
}

/** The buyer buys the region through Realty, with money given by the console. */
export async function buy(server: ServerWrapper, buyer: PlayerWrapper, region: string): Promise<void> {
    await server.execute(`eco give ${buyer.username} 100`);
    buyer.chat(`/realty buy ${region}`);
    await expect(buyer).toHaveReceivedMessage('You bought region');
}

/** A freehold around a fresh spot, held by the occupant and for sale. */
export async function plotForSale(server: ServerWrapper, staff: PlayerWrapper, occupant: PlayerWrapper, buyer: PlayerWrapper) {
    const { x, z, region } = await scene(server, staff, occupant, buyer);
    await defineRegion(staff, region, around(x, z));
    await freehold(staff, region, occupant);
    return { x, z, region };
}

/** How many vaults the player owns, once the sweep has had time to store them. */
export async function vaultsOf(player: PlayerWrapper, expected: number): Promise<void> {
    const count = () => query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${player.bot.player.uuid}'`)[0];
    await waitUntil(() => count() === String(expected),
        { timeout: 10000, message: `${player.username} has ${count()} vaults, expected ${expected}` });
}

/** The sweep's delay is one second; this is long enough for it to have run and done nothing. */
export const SWEEP_HAS_RUN = 4000;

/** A WorldGuard region drawn as a polygon through these corners, from minY to maxY. */
export async function definePolygon(staff: PlayerWrapper, region: string, corners: Array<[number, number]>,
                                    minY: number, maxY: number): Promise<void> {
    staff.chat('//sel poly');
    await sleep(300);
    corners.forEach(([x, z], index) => {
        // The first corner starts the polygon and every further one adds a point. The last
        // carries the top height, so the polygon spans minY to maxY.
        const y = index === corners.length - 1 ? maxY : minY;
        staff.chat(`${index === 0 ? '//pos1' : '//pos2'} ${x},${y},${z}`);
    });
    await sleep(500);
    staff.chat(`/rg define ${region}`);
    await expect(staff).toHaveReceivedMessage(region);
    staff.chat('//sel cuboid');
    await sleep(200);
}
