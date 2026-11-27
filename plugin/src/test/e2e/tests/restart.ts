import { pluginsFolder } from './files.js';

// What the two halves of the restart test share. Not in either spec file: importing a spec
// file runs its tests.

/** Where the first half tells the second what it left behind. In the server's folder. */
export function stateFile(): string {
    return `${pluginsFolder()}/../restart-state.json`;
}

/** The server's log since its last start. */
export function logFile(): string {
    return `${pluginsFolder()}/../logs/latest.log`;
}

/** Fixed names: a bot must come back as the same player after the restart. */
export const NAMES = { staff: 'vs_r_staff', owner: 'vs_r_owner', buyer: 'vs_r_buyer', editor: 'vs_r_editor' };

/** Far from every other spec's spot, and the same in both halves. */
export const SPOT = { x: 90000, z: 90000 };
