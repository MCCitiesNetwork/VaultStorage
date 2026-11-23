import { expect, sleep, waitUntil, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';
import nbt from 'prismarine-nbt';
import { Vec3 } from 'vec3';
import { query } from './database.js';
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
    await expect(staff).toHaveReceivedMessage('Capture mode is on');
    await rightClick(staff, x + 2, FLOOR, z);
    await expect(staff).toHaveReceivedMessage('Vaulted.');
    return { x, z };
}

/** Walks the owner's own menu to the action screen of their first vault. */
export async function ownVaultActions(player: PlayerWrapper, dialogs: Dialogs): Promise<Dialog> {
    player.chat('/vault');
    (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
    const list = await dialogTitled(dialogs, 'Browse Vaults');
    list.click(list.button('Vault #').label);
    return dialogTitled(dialogs, 'Vault Action');
}

/** Walks the admin menu to the action screen of the vault belonging to this owner. */
export async function adminVaultActions(admin: PlayerWrapper, dialogs: Dialogs, ownerName: string): Promise<Dialog> {
    admin.chat('/vault admin');
    (await dialogTitled(dialogs, 'Vault Storage')).click('Browse Vaults');
    // The admin list holds every vault the dev database has collected, so search for the owner.
    (await dialogTitled(dialogs, 'Browse Vaults')).click('Search', { QUERY: ownerName });
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

/** Locks whatever block is at the spot for the owner, with Bolt's /lock. */
export async function lock(owner: PlayerWrapper, x: number, z: number, y: number = FLOOR): Promise<void> {
    owner.chat('/lock');
    await sleep(500);
    await rightClick(owner, x, y, z);
    await sleep(500);
}

/** Puts the player in capture mode. */
export async function captureMode(player: PlayerWrapper): Promise<void> {
    player.chat('/vault capture');
    await expect(player).toHaveReceivedMessage('Capture mode is on');
}

/** How many vaults the player owns. */
export function vaultCount(player: PlayerWrapper): number {
    return Number(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${player.bot.player.uuid}'`)[0]);
}

/** How many item rows the player's vaults hold between them. */
export function vaultItemCount(player: PlayerWrapper): number {
    return Number(query('SELECT COUNT(*) FROM VaultItem i JOIN Vault v ON v.uuid = i.vaultUuid '
        + `WHERE v.ownerUuid = '${player.bot.player.uuid}'`)[0]);
}

/** Waits until the player owns this many vaults. */
export async function expectVaults(player: PlayerWrapper, expected: number): Promise<void> {
    await waitUntil(() => vaultCount(player) === expected,
        { timeout: 10000, message: `${player.username} has ${vaultCount(player)} vaults, expected ${expected}` });
}

/**
 * The owner places their first vault through the menus, by clicking the top of the ground
 * block at the spot. Returns once the plugin has answered; the caller asserts on the answer.
 */
export async function placeFirstVault(owner: PlayerWrapper, dialogs: Dialogs, x: number, z: number): Promise<void> {
    (await ownVaultActions(owner, dialogs)).click('Place Block');
    (await dialogTitled(dialogs, 'Vault Placement')).click('Start Placement');
    await sleep(300);
    await rightClick(owner, x, FLOOR - 1, z);
}

/** Right-clicks an entity. Written as a packet, like block clicks: see rightClick in world.ts. */
export function clickEntity(player: PlayerWrapper, entityId: number): void {
    // "Interact at": the form a client sends with the point it clicked. The plain interact form
    // does not get through to this server from a 1.21.11 bot.
    (player.bot as any)._client.write('use_entity', {
        target: entityId, mouse: 2, x: 0, y: 0, z: 0, sneaking: false, hand: 0,
    });
}

/** The id, as this bot knows it, of the entity of this kind standing in the block column. */
export async function entityIdAt(player: PlayerWrapper, kind: string, x: number, z: number): Promise<number> {
    const find = () => Object.values((player.bot as any).entities as Record<string, any>).find((entity) =>
        entity.name === kind && Math.floor(entity.position.x) === x && Math.floor(entity.position.z) === z);
    await waitUntil(() => find() !== undefined, { timeout: 5000, message: `${player.username} never saw a ${kind} at ${x},${z}` });
    return find().id;
}

/**
 * The action bar lines a player is sent. Bolt and the capture and placement modes answer
 * there, and a bot's chat log does not hold them.
 */
export class ActionBars {
    private readonly lines: string[] = [];

    constructor(player: PlayerWrapper) {
        const client = (player.bot as any)._client;
        const read = (component: any): string => {
            if (component == null) {
                return '';
            }
            if (typeof component === 'string') {
                // Older protocol versions send JSON text.
                try {
                    return read(JSON.parse(component));
                } catch {
                    return component;
                }
            }
            if (Array.isArray(component)) {
                return component.map(read).join('');
            }
            if (component.type !== undefined && component.value !== undefined) {
                // Raw NBT: a string tag, or a compound of text and extra.
                return component.type === 'string' ? component.value : read(nbt.simplify(component));
            }
            return (component.text ?? component[''] ?? '') + (component.extra ?? []).map(read).join('');
        };
        client.on('action_bar', (packet: any) => this.lines.push(read(packet.text)));
        client.on('system_chat', (packet: any) => {
            if (packet.isActionBar) {
                this.lines.push(read(packet.content));
            }
        });
    }

    /** Waits for an action bar line containing the text, among those sent from now on. */
    async next(text: string, timeoutMs = 6000): Promise<string> {
        const from = this.lines.length;
        await waitUntil(() => this.lines.slice(from).some((line) => line.includes(text)), {
            timeout: timeoutMs,
            message: `No action bar line containing "${text}". Last lines: ${this.lines.slice(-3).join(' // ')}`,
        });
        return this.lines.slice(from).find((line) => line.includes(text))!;
    }
}

/** Turns the player to face the middle of a block, so the server knows what they look at. */
export async function lookAtBlock(player: PlayerWrapper, x: number, y: number, z: number): Promise<void> {
    await (player.bot as any).lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5), true);
    await sleep(300);
}

