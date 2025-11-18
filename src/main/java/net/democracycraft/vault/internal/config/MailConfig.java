package net.democracycraft.vault.internal.config;

import java.io.Serializable;

/**
 * Mail configuration persisted via AutoYML.
 *
 * <p>Placeholders supported in MiniMessage template:
 * <ul>
 *   <li>%sender% — the display name of the player who triggered the action.</li>
 *   <li>%recipient% — the display name of the mail recipient (vault owner).</li>
 * </ul>
 *
 * <p>Formatting uses MiniMessage. Example tags: <gold>, <yellow>, <bold>, etc.
 * If MiniMessage parsing fails, the plugin will fall back to plain text.</p>
 */
public class MailConfig implements Serializable {

    /**
     * Header written to the YAML file explaining placeholders and formatting.
     */
    public static final String HEADER = String.join("\n",
            "Mail configuration for VaultStorage",
            "Placeholders:",
            " - %sender%: display name of the player sending the mail.",
            " - %recipient%: display name of the mail recipient (vault owner).",
            " - %region%: region id where the vault was created.",
            "Formatting: MiniMessage is supported (e.g. <gold>, <yellow>, <bold>).",
            "If MiniMessage parsing fails, the message will be sent as plain text.");

    /**
     * MiniMessage template used when a vault is created.
     * Supports placeholders %sender% and %recipient%.
     */
    public String vaultCreatedMessage = "<gold>%sender%</gold> created a new vault for you:  <yellow>%recipient%</yellow> <gray>in region</gray> <yellow>%region%</yellow>";

    /** No-arg constructor required by the AutoYML loader. */
    public MailConfig() {}
}

