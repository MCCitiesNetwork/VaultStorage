package net.democracycraft.vault.api.data;

import org.bukkit.Material;
import org.bukkit.block.Block;
import java.util.UUID;

public record ScanResult(Block block, UUID owner, Material type) {
}

