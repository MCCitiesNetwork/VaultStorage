import { definePlugin } from '@plugwright/runner';

// Lets live-test bots behave like 1.21.11 clients on the 26.3 server: connected, with physics.
//
// 1. Every bot joins as 1.21.11, the newest version mineflayer speaks; ViaVersion and
//    ViaBackwards translate.
// 2. A client ends each tick with tick_end; mineflayer 4.39 never sends it. Since 26.3 the server
//    refuses a second position packet until the tick has ended (receivedPositionThisTick in
//    ServerGamePacketListenerImpl) and kicks with "Invalid move player packet received". So
//    every movement packet the bot writes is followed by tick_end, wherever it is sent from: a
//    physics step, or the answer to a teleport.
// 3. plugwright names a bot pw_ plus four hex digits, which repeat within a long run, and a name
//    is a player: the second bot would own the first one's vaults. Invented names are replaced
//    with ones that cannot repeat; a name a test asked for is kept.
// 4. The server randomises where a player spawns. A spawn radius of 0 puts every bot on the same
//    block.
const BOT_VERSION = '1.21.11';
const MOVEMENT_PACKETS = new Set(['position', 'position_look', 'look', 'flying']);

export default definePlugin({
  name: 'test-server',
  async setup({ session }) {
    const createBot = session.createBot.bind(session);
    const run = Date.now().toString(36).slice(-4);
    let made = 0;
    const unique = (name: string) =>
      /^pw_[0-9a-f]{4}$/.test(name) ? `pw_${run}_${(made++).toString(36)}` : name;

    session.createBot = (options) => {
      const bot = createBot({ ...options, username: unique(options.username), version: BOT_VERSION });
      const client: any = (bot as any)._client;
      const write = client.write.bind(client);
      client.write = (name: string, params: unknown) => {
        write(name, params);
        if (MOVEMENT_PACKETS.has(name)) {
          write('tick_end', {});
        }
      };
      return bot;
    };

    const console: any = (session as any).console;
    for (const rule of ['respawn_radius', 'spawn_radius', 'spawnRadius']) {
      try {
        await console.execute(`gamerule ${rule} 0`);
      } catch {
        // Only one of these names exists on a given Minecraft version.
      }
    }
  },
});
