import { execFileSync } from 'node:child_process';

// The dev database from compose.dev.yml, which the test server uses.
const CONTAINER = 'vaultstorage-dev-mariadb';

/** Stops the database, as an outage would. */
export function stopDatabase(): void {
    execFileSync('docker', ['stop', CONTAINER], { stdio: 'ignore' });
}

/** Starts the database again and waits until it accepts connections. */
export function startDatabase(): void {
    execFileSync('docker', ['start', CONTAINER], { stdio: 'ignore' });
    for (let attempt = 0; attempt < 60; attempt++) {
        const health = execFileSync('docker', ['inspect', '--format', '{{.State.Health.Status}}', CONTAINER]).toString().trim();
        if (health === 'healthy') {
            return;
        }
        execFileSync('sleep', ['1']);
    }
    throw new Error('the database did not come back');
}

/** Runs SQL against the dev database. */
export function sql(statement: string): void {
    execFileSync('docker', ['exec', CONTAINER, 'mariadb', '-uvaultstorage', '-pvaultstorage', 'vault_storage', '-e', statement],
        { stdio: 'ignore' });
}

/** Runs a query against the dev database and returns its rows, one per line, columns tab-separated. */
export function query(statement: string): string[] {
    const output = execFileSync('docker',
        ['exec', CONTAINER, 'mariadb', '-uvaultstorage', '-pvaultstorage', 'vault_storage', '-N', '-B', '-e', statement]).toString();
    return output.split('\n').filter((line) => line.length > 0);
}

/**
 * Stores empty vaults for an owner straight in the database, a minute apart so their order is
 * fixed. For tests that need more vaults than it is reasonable to capture one by one.
 */
export function seedVaults(ownerUuid: string, count: number): void {
    const world = '99999999-0000-0000-0000-000000000001';
    sql(`INSERT INTO World (uuid, name) VALUES ('${world}', 'seeded') ON DUPLICATE KEY UPDATE name = name`);
    const rows: string[] = [];
    for (let i = 0; i < count; i++) {
        rows.push(`(UUID(), '${ownerUuid}', '${world}', ${i}, 64, 0, 'CHEST', NULL, NULL, `
            + `'2026-01-01 00:00:00' + INTERVAL ${i} MINUTE, '2026-01-01 00:00:00' + INTERVAL ${i} MINUTE)`);
    }
    sql('INSERT INTO Vault (uuid, ownerUuid, worldUuid, x, y, z, material, blockData, status, createdTime, updatedTime) VALUES '
        + rows.join(', '));
}

/**
 * Runs a query against another database on the dev server, as root: the economy plugins keep
 * theirs in `treasury`, which the plugin's own account cannot read.
 */
export function queryDatabase(database: string, statement: string): string[] {
    const output = execFileSync('docker',
        ['exec', CONTAINER, 'mariadb', '-uroot', '-proot', database, '-N', '-B', '-e', statement]).toString();
    return output.split('\n').filter((line) => line.length > 0);
}

/** Runs a long statement against the dev database, sent on standard input: an argument has a size limit. */
export function sqlFromInput(statement: string): void {
    execFileSync('docker', ['exec', '-i', CONTAINER, 'mariadb', '-uvaultstorage', '-pvaultstorage', 'vault_storage'],
        { input: statement, stdio: ['pipe', 'ignore', 'inherit'] });
}
