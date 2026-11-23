import * as fs from 'node:fs';
import * as path from 'node:path';

// The test server's files on disk, for specs that edit a config and reload.

/** The test server's plugins folder. */
export function pluginsFolder(): string {
    let folder = process.cwd();
    for (let depth = 0; depth < 6; depth++) {
        for (const candidate of [
            path.join(folder, 'generated/local/run/plugins'),
            path.join(folder, 'src/test/e2e/generated/local/run/plugins'),
            path.join(folder, 'plugin/src/test/e2e/generated/local/run/plugins'),
        ]) {
            if (fs.existsSync(candidate)) {
                return candidate;
            }
        }
        folder = path.dirname(folder);
    }
    throw new Error(`The test server's plugins folder was not found from ${process.cwd()}`);
}

/** A file under plugins/VaultStorage, such as `settings.yml` or `modules/realty-adapter/config.yml`. */
export function pluginFile(relative: string): string {
    return path.join(pluginsFolder(), 'VaultStorage', relative);
}

/**
 * Runs the body with the file's text changed, and puts the original back afterwards whatever
 * happens. The caller reloads inside the body and again after it.
 */
export async function withFile(file: string, change: (text: string) => string, body: () => Promise<void>): Promise<void> {
    const original = fs.readFileSync(file, 'utf8');
    try {
        fs.writeFileSync(file, change(original));
        await body();
    } finally {
        fs.writeFileSync(file, original);
    }
}
