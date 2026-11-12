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

    @NotNull public CompletableFuture<String> getPlayerName(@NotNull UUID uuid) {
        String hex = uuid
                .toString()
                .replace("-", "")
                .substring(16);
        long xuid = Long.parseUnsignedLong(hex, 16);
        return floodgateApi.getGamertagFor(xuid);
    }

}
