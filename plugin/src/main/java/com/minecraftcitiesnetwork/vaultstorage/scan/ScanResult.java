package com.minecraftcitiesnetwork.vaultstorage.scan;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Material;
import org.bukkit.block.Block;
import java.util.UUID;

public record ScanResult(@NotNull Block block, @Nullable UUID owner, @NotNull Material type) {
}

