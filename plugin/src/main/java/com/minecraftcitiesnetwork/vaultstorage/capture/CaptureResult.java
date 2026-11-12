package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

public sealed interface CaptureResult {

    /** The items are stored and the container is gone from the world. */
    record Vaulted(@NotNull UUID vault) implements CaptureResult {
    }

    /** The container changed while it was being saved. Nothing was stored and nothing was removed. */
    record ChangedMeanwhile() implements CaptureResult {
    }

    /** The database failed. The container is still in the world with its items. */
    record Failed(@NotNull Throwable cause) implements CaptureResult {
    }
}
