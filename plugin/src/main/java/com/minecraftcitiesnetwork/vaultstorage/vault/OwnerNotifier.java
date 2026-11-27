package com.minecraftcitiesnetwork.vaultstorage.vault;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Gets a message to a vault's owner, who is usually offline when their container is vaulted.
 * Best effort: how and when it arrives is the implementation's business.
 */
@FunctionalInterface
public interface OwnerNotifier {

    void deliver(@NotNull UUID recipient, @NotNull Component title, @NotNull Component message);
}
