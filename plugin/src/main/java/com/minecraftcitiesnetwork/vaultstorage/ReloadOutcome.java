package com.minecraftcitiesnetwork.vaultstorage;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** How a reload went. */
public sealed interface ReloadOutcome {

    /** settings.yml, messages.yml and every module that reloads are in use. */
    record Reloaded() implements ReloadOutcome {
    }

    /** One of the two files could not be read, so neither took effect. No module was reloaded. */
    record FilesRejected() implements ReloadOutcome {
    }

    /** Both files are in use; these modules failed to reload and keep their old settings. */
    record ModulesFailed(@NotNull List<String> modules) implements ReloadOutcome {
    }
}
