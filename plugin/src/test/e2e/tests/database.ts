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
