import { expect, sleep, test, waitUntil, type PlayerWrapper } from '@plugwright/runner';
import { query } from './database.js';
import { expectBlock, FLOOR, freshSpot, rightClick, standAt } from './world.js';

// The less common things a capture meets: a double chest, and an item frame.

/** Right-clicks an entity. Written as a packet, like block clicks: see rightClick in world.ts. */
function clickEntity(player: PlayerWrapper, entityId: number): void {
    // "Interact at": the form a client sends with the point it clicked. The plain interact form
    // does not get through to this server from a 1.21.11 bot.
    (player.bot as any)._client.write('use_entity', {
        target: entityId, mouse: 2, x: 0, y: 0, z: 0, sneaking: false, hand: 0,
    });
}

test('one half of a double chest is vaulted and the other half stays locked for its owner',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        const leftX = x + 2;
        const rightX = x + 3;
        // A chest's half is named from its own point of view: facing north, its left is west.
        await server.execute(`setblock ${leftX} ${FLOOR} ${z} minecraft:chest[facing=north,type=left]`);
        await server.execute(`setblock ${rightX} ${FLOOR} ${z} minecraft:chest[facing=north,type=right]`);
        // One item in each half.
        await server.execute(`minecraft:item replace block ${leftX} ${FLOOR} ${z} container.0 with minecraft:diamond 3`);
        await server.execute(`minecraft:item replace block ${rightX} ${FLOOR} ${z} container.0 with minecraft:emerald 3`);
        owner.chat('/lock');
        await sleep(500);
        await rightClick(owner, leftX, FLOOR, z);
        await sleep(500);

        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture mode is on');
        await rightClick(player, leftX, FLOOR, z);
        await expect(player).toHaveReceivedMessage('Vaulted.');
        await expectBlock(server, leftX, FLOOR, z, 'air', 'first-half-gone');
        await expectBlock(server, rightX, FLOOR, z, 'chest', 'second-half-stays');

        // The second half can only be captured if it still has a lock owner: the capture of the
        // first half re-protected it for the same owner.
        await rightClick(player, rightX, FLOOR, z);
        // 'Vaulted.' is already in the log from the first half, so wait for the save instead.
        await sleep(1500);
        await expectBlock(server, rightX, FLOOR, z, 'air', 'second-half-gone');
        const ownerUuid = owner.bot.player.uuid;
        expect(query(`SELECT COUNT(*) FROM Vault WHERE ownerUuid = '${ownerUuid}'`)).toEqual(['2']);
    });

test('a locked item frame is vaulted with what it displays, and removed',
    { requires: { consoleOutput: 'full' } }, async ({ player, createPlayer, server }) => {
        const owner = await createPlayer();
        const { x, z } = freshSpot();
        await Promise.all([
            server.execute(`op ${player.username}`),
            server.execute(`op ${owner.username}`),
        ]);
        await standAt(server, player, x, z);
        await standAt(server, owner, x, z + 1);
        const frameX = x + 2;
        // Facing 1 is up: the frame lies on top of the ground block.
        await server.execute(`summon minecraft:item_frame ${frameX} ${FLOOR} ${z} {Facing:1b,block_pos:[I;${frameX},${FLOOR},${z}],Item:{id:"minecraft:diamond",count:1}}`);

        const frameId = async (bot: PlayerWrapper): Promise<number> => {
            const find = () => Object.values((bot.bot as any).entities as Record<string, any>).find((entity) =>
                entity.name === 'item_frame' && Math.floor(entity.position.x) === frameX && Math.floor(entity.position.z) === z);
            await waitUntil(() => find() !== undefined, { timeout: 5000, message: `${bot.username} never saw the item frame` });
            return find().id;
        };

        owner.chat('/lock');
        await sleep(500);
        clickEntity(owner, await frameId(owner));
        await sleep(700);

        player.chat('/vault capture');
        await expect(player).toHaveReceivedMessage('Capture mode is on');
        clickEntity(player, await frameId(player));
        await expect(player).toHaveReceivedMessage('Vaulted.');

        await server.execute(`execute unless entity @e[type=minecraft:item_frame,x=${frameX},y=${FLOOR},z=${z},distance=..2] run say frame-is-gone`);
        await expect(server).toHaveReceivedMessage('frame-is-gone');
        // The frame and the diamond it showed, in the owner's vault.
        const ownerUuid = owner.bot.player.uuid;
        expect(query(`SELECT COUNT(*) FROM VaultItem i JOIN Vault v ON v.uuid = i.vaultUuid WHERE v.ownerUuid = '${ownerUuid}'`))
            .toEqual(['2']);
    });
