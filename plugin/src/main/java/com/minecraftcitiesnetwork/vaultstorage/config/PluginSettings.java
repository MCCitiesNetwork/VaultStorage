package com.minecraftcitiesnetwork.vaultstorage.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/** {@code settings.yml}. Reloadable: consumers read it through the plugin's reference each time. */
@ConfigSerializable
public record PluginSettings(
        @Setting("scan") @Required @NotNull Scan scan,
        @Setting("vault-list") @Required @NotNull VaultList vaultList
) {

    @ConfigSerializable
    public record Scan(
            @Setting("batch-size") int batchSize,
            @Setting("cache-ttl-seconds") long cacheTtlSeconds,
            @Setting("cooldown-seconds") long cooldownSeconds
    ) {
        private static final int DEFAULT_BATCH_SIZE = 20;

        public Scan {
            // An absent primitive key deserialises to 0, so 0 means "use the default".
            if (batchSize <= 0) {
                batchSize = DEFAULT_BATCH_SIZE;
            }
            // Zero is a real choice for these two: no cache, no cooldown.
            cacheTtlSeconds = Math.max(0, cacheTtlSeconds);
            cooldownSeconds = Math.max(0, cooldownSeconds);
        }
    }

    @ConfigSerializable
    public record VaultList(@Setting("page-size") int pageSize) {
        private static final int DEFAULT_PAGE_SIZE = 20;
        /** More than this does not fit a dialog comfortably. */
        private static final int MAX_PAGE_SIZE = 40;

        public VaultList {
            if (pageSize <= 0) {
                pageSize = DEFAULT_PAGE_SIZE;
            }
            pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        }
    }
}
