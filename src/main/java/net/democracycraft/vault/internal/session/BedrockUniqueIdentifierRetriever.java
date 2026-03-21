package net.democracycraft.vault.internal.session;

import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BedrockUniqueIdentifierRetriever {

    private final FloodgateApi floodgateApi;

    public BedrockUniqueIdentifierRetriever(@NotNull FloodgateApi floodgateApi) {
        this.floodgateApi = floodgateApi;
    }

    @NotNull public CompletableFuture<UUID> getUniqueIdentifier(@NotNull String playerName) {
        return floodgateApi.getUuidFor(playerName);
    }

}
