import { poll, sleep, type PlayerWrapper } from '@plugwright/runner';
import nbt from 'prismarine-nbt';

/**
 * Reads and clicks Paper dialogs as a real client does. The server sends show_dialog with the screen as
 * NBT; a button is a minecraft:dynamic/custom action whose "additions" identify Paper's click callback.
 * Clicking sends custom_click_action with that id and the additions (plus any input values) as payload.
 */

type Tag = { type: string; value: any };

// The plain text of a chat component, as simplified NBT.
function text(component: any): string {
    if (component == null) {
        return '';
    }
    if (typeof component === 'string') {
        return component;
    }
    if (Array.isArray(component)) {
        return component.map(text).join('');
    }
    const own = component.text ?? component[''] ?? '';
    return own + (component.extra ?? []).map(text).join('');
}

// custom_click_action's packet id in the protocol the bot speaks, so the helper follows the bot's version.
function customClickActionId(player: PlayerWrapper): number {
    const mappings: Record<string, string> = (player.bot as any).registry.protocol.play.toServer.types.packet[1][0].type[1].mappings;
    const id = Object.keys(mappings).find((key) => mappings[key] === 'custom_click_action');
    if (id === undefined) {
        throw new Error(`The bot's protocol (${player.bot.version}) has no custom_click_action packet`);
    }
    return Number(id);
}

const varInt = (value: number): Buffer => nbt.protos.big.createPacketBuffer('varint', value);

// The game encodes the payload as a length-prefixed optional tag (an absent payload is an end tag), but the
// bot library writes a presence flag and no length, which gets the bot disconnected. So the packet is
// encoded by hand: packet id, the callback id, then the payload's length and its network (nameless) NBT.
function customClickAction(packetId: number, id: string, payload: Tag): Buffer {
    const tag: Buffer = nbt.protos.big.createPacketBuffer('anonymousNbt', payload);
    const idBytes = Buffer.from(id, 'utf8');
    return Buffer.concat([varInt(packetId), varInt(idBytes.length), idBytes, varInt(tag.length), tag]);
}

function tagFor(value: number | string | boolean): Tag {
    if (typeof value === 'number') {
        return { type: 'float', value };
    }
    if (typeof value === 'boolean') {
        return { type: 'byte', value: value ? 1 : 0 };
    }
    return { type: 'string', value };
}

export interface DialogButton {
    label: string;
    // The raw NBT of the button's action, kept so a click can echo it back.
    action: Tag | undefined;
}

export class Dialog {
    readonly title: string;
    // The body's text blocks, one per line.
    readonly body: string;
    readonly buttons: DialogButton[] = [];

    constructor(private readonly player: PlayerWrapper, raw: any) {
        const data: Tag = raw.dialog?.data ?? raw.dialog;
        const simple: any = nbt.simplify(data as any);
        this.title = text(simple.title);
        // A dialog with one body element sends it as a compound, not a list of one.
        const body = simple.body == null ? [] : Array.isArray(simple.body) ? simple.body : [simple.body];
        this.body = body.map((entry: any) => text(entry.contents)).join('\n');
        const fields = data.value;
        // Takes a button compound's fields.
        const add = (button: any) => {
            if (button) {
                this.buttons.push({ label: text(nbt.simplify(button.label)), action: button.action });
            }
        };
        // multi_action has a list of buttons; confirmation has yes/no; notice has one action.
        for (const entry of fields.actions?.value?.value ?? []) {
            add(entry);
        }
        add(fields.yes?.value);
        add(fields.no?.value);
        add(fields.action?.value);
        add(fields.exit_action?.value);
    }

    // The button labels, for error messages.
    labels(): string {
        return this.buttons.map((button) => button.label).join(', ');
    }

    // The button with this label, or else the first whose label contains it.
    button(label: string): DialogButton {
        const found = this.buttons.find((button) => button.label === label)
            ?? this.buttons.find((button) => button.label.includes(label));
        if (!found) {
            throw new Error(`No button "${label}" in dialog "${this.title}". Buttons: ${this.labels()}`);
        }
        return found;
    }

    // Clicks the button with this label. inputs are the dialog's input values by key, such as a slider.
    click(label: string, inputs: Record<string, number | string | boolean> = {}): void {
        const action = this.button(label).action?.value;
        if (!action) {
            throw new Error(`Button "${label}" has no action`);
        }
        const additions: Record<string, Tag> = { ...(action.additions?.value ?? {}) };
        for (const [key, value] of Object.entries(inputs)) {
            additions[key] = tagFor(value);
        }
        const packet = customClickAction(customClickActionId(this.player), action.id.value,
            { type: 'compound', name: '', value: additions } as Tag);
        (this.player.bot as any)._client.writeRaw(packet);
    }
}

export class Dialogs {
    private readonly received: any[] = [];

    constructor(private readonly player: PlayerWrapper) {
        (player.bot as any)._client.on('show_dialog', (packet: any) => this.received.push(packet));
    }

    // The next dialog shown after the last one read.
    async next(timeoutMs = 5000): Promise<Dialog> {
        const packet = await poll(() => this.received.shift(), {
            timeout: timeoutMs, message: `No dialog shown within ${timeoutMs}ms`,
        });
        // The server sometimes ignores a click sent within milliseconds of the dialog arriving. A person never
        // clicks that fast, so wait one tick before handing the dialog over.
        await sleep(50);
        return new Dialog(this.player, packet);
    }
}
