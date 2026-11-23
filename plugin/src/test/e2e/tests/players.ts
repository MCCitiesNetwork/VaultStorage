import { sleep, type PlayerWrapper, type ServerWrapper } from '@plugwright/runner';

// Players who are not operators. Operators hold every permission, so a test of what a
// permission allows needs a player with exactly the nodes it names, through LuckPerms.

/** What every ordinary player on the network gets: the menu. View and place are true by default. */
export const MENU = 'vaultstorage.command.vault';

/** Takes operator status away and gives the player exactly these nodes. */
export async function ordinary(server: ServerWrapper, player: PlayerWrapper, nodes: string[] = [MENU]): Promise<void> {
    await server.execute(`deop ${player.username}`);
    for (const node of nodes) {
        await server.execute(`lp user ${player.username} permission set ${node} true`);
    }
    // LuckPerms applies a change a moment after the command returns.
    await sleep(1200);
}

/** Makes these players operators. */
export async function operators(server: ServerWrapper, ...players: PlayerWrapper[]): Promise<void> {
    await Promise.all(players.map((player) => server.execute(`op ${player.username}`)));
}
