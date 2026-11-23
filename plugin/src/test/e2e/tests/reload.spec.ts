import { expect, test } from '@plugwright/runner';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { Dialogs } from './dialogs.js';

// /vault reload re-reads messages.yml. A file that does not parse changes nothing.

function messagesFile(): string {
    let folder = process.cwd();
    for (let depth = 0; depth < 6; depth++) {
        for (const candidate of [
            path.join(folder, 'generated/local/run/plugins/VaultStorage/messages.yml'),
            path.join(folder, 'src/test/e2e/generated/local/run/plugins/VaultStorage/messages.yml'),
            path.join(folder, 'plugin/src/test/e2e/generated/local/run/plugins/VaultStorage/messages.yml'),
        ]) {
            if (fs.existsSync(candidate)) {
                return candidate;
            }
        }
        folder = path.dirname(folder);
    }
    throw new Error(`messages.yml not found from ${process.cwd()}`);
}

test('a reload picks up reworded messages and refuses a broken file', async ({ player, server }) => {
    await server.execute(`op ${player.username}`);
    const file = messagesFile();
    const shipped = fs.readFileSync(file, 'utf8');
    const dialogs = new Dialogs(player);
    try {
        fs.writeFileSync(file, shipped.replace('Vault Storage</bold>', 'Reworded Menu</bold>'));
        player.chat('/vault reload');
        await expect(player).toHaveReceivedMessage('VaultStorage reloaded.');
        player.chat('/vault');
        expect((await dialogs.next()).title).toBe('Reworded Menu');

        fs.writeFileSync(file, "capture-menu:\n  title: 'unterminated\n    broken: [\n");
        player.chat('/vault reload');
        await expect(player).toHaveReceivedMessage('The reload failed');
        player.chat('/vault');
        expect((await dialogs.next()).title).toBe('Reworded Menu');
    } finally {
        fs.writeFileSync(file, shipped);
        await server.execute('vault reload');
    }
});
