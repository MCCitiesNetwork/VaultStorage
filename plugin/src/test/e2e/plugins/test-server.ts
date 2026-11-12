import { definePlugin, waitUntil } from '@plugwright/runner';

// Keeps live-test bots connected to the 26.3 server through ViaBackwards, standing still.
//
// 1. The server randomises where a player spawns, so a bot starts somewhere the server does not
//    expect, gets "moved too quickly" and is snapped back over and over. A spawn radius of 0 stops
//    that.
// 2. mineflayer sends a position packet about once a second even when the bot stands still. Through
//    ViaBackwards the server rejects it and kicks the bot with "Invalid move player packet
//    received". A server does not need those packets, so they are dropped.
// 3. With physics on, a bot teleported before its chunk arrives falls through the floor the server
//    sees it standing on, and its next position_look jumps several blocks and gets it kicked. So
//    physics is off. mineflayer only sends look packets from its physics tick, so look() is wrapped
//    to send one itself: commands such as /updateitems read the block the player faces.
// 4. mineflayer answers a teleport that comes soon after a respawn or join with a position_look
//    1.5 s late, carrying the teleport's own facing, which would undo a look sent in between. So
//    the look packet waits until every teleport has been answered (mineflayer emits forcedMove
//    once it has).
//
// Teleport answers (teleport_confirm and position_look) still go through. Specs that need a walking
// bot will have to revisit this.
const RADIANS_TO_DEGREES = 180 / Math.PI;

// Every bot joins as a 1.21.11 client; ViaVersion and ViaBackwards on the server translate.
const BOT_VERSION = '1.21.11';

export default definePlugin({
  name: 'test-server',
  async setup({ session }) {
    const createBot = session.createBot.bind(session);
    session.createBot = (options) => createBot({ ...options, version: BOT_VERSION });
    const console: any = (session as any).console;
    for (const rule of ['respawn_radius', 'spawn_radius', 'spawnRadius']) {
      try {
        await console.execute(`gamerule ${rule} 0`);
      } catch {
        // Only one of these names exists on a given Minecraft version.
      }
    }
  },
  onPlayerCreate(player) {
    const bot: any = player.bot;
    bot.physicsEnabled = false;
    bot.once('spawn', () => {
      bot.physicsEnabled = false;
    });
    const client = bot._client;
    const write = client.write.bind(client);
    client.write = (name: string, params: unknown) => {
      if (name === 'position' || name === 'flying') {
        return;
      }
      return write(name, params);
    };
    let teleports = 0;
    let answered = 0;
    client.on('position', () => {
      teleports++;
    });
    bot.on('forcedMove', () => {
      answered++;
    });
    const look = bot.look.bind(bot);
    bot.look = async (yaw: number, pitch: number, force?: boolean) => {
      await waitUntil(() => answered >= teleports,
        { timeout: 5000, message: `${player.username} never answered its teleport` });
      await look(yaw, pitch, force);
      // The same conversion mineflayer's physics tick uses for its look packets.
      write('look', {
        yaw: (Math.PI - bot.entity.yaw) * RADIANS_TO_DEGREES,
        pitch: -bot.entity.pitch * RADIANS_TO_DEGREES,
        onGround: true,
        flags: { onGround: true },
      });
    };
  },
});
