package com.minecraftcitiesnetwork.vaultstorage.config;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** The {@code messages.yml} that ships in the jar, for tests that assert on wording. */
public final class TestMessages {

    private TestMessages() {
    }

    public static @NotNull MessageContainer shipped() {
        MessageContainer messages = new MessageContainer();
        messages.load(shippedNode());
        return messages;
    }

    /** Every key in the shipped file as a dotted path. */
    public static @NotNull List<String> shippedKeys() {
        List<String> keys = new ArrayList<>();
        collect(shippedNode(), "", keys);
        return keys;
    }

    private static void collect(@NotNull ConfigurationNode node, @NotNull String path, @NotNull List<String> keys) {
        if (!node.isMap()) {
            keys.add(path);
            return;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
            collect(child.getValue(), path.isEmpty() ? child.getKey().toString() : path + "." + child.getKey(), keys);
        }
    }

    private static @NotNull ConfigurationNode shippedNode() {
        try {
            String text = ConfigFiles.bundled(TestMessages.class, "messages.yml");
            return YamlConfigurationLoader.builder()
                    .source(() -> new BufferedReader(new StringReader(text))).build().load();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
