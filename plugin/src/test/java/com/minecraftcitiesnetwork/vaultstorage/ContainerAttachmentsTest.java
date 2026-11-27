package com.minecraftcitiesnetwork.vaultstorage;

import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerAttachments;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** What the host sees attached to containers: what the modules tell it, taken together. */
class ContainerAttachmentsTest {

    /** A block that is only ever compared by identity. */
    private static Block block() {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A plugin with one sign on one container. Records what it was asked to remove. */
    private static final class OneShop implements ContainerAttachments {
        final Block sign = block();
        final Block container = block();
        final List<Block> removed = new ArrayList<>();

        @Override
        public boolean isAttachment(@Nullable Block block) {
            return block == this.sign;
        }

        @Override
        public @NotNull List<Block> attachedTo(@Nullable Block containerBlock) {
            return containerBlock == this.container ? List.of(this.sign) : List.of();
        }

        @Override
        public boolean remove(@Nullable Player actor, @Nullable Block signBlock) {
            this.removed.add(signBlock);
            return true;
        }
    }

    /** A module whose plugin is broken. */
    private static final class Broken implements ContainerAttachments {
        @Override
        public boolean isAttachment(@Nullable Block block) {
            throw new IllegalStateException("broken");
        }

        @Override
        public @NotNull List<Block> attachedTo(@Nullable Block containerBlock) {
            throw new IllegalStateException("broken");
        }

        @Override
        public boolean remove(@Nullable Player actor, @Nullable Block signBlock) {
            throw new IllegalStateException("broken");
        }
    }

    private final Integrations integrations = new Integrations(Logger.getAnonymousLogger());

    @Test
    void withNoModuleNothingIsAttachedToAnything() {
        Block anything = block();

        Assertions.assertFalse(this.integrations.attachments().isAttachment(anything));
        Assertions.assertEquals(List.of(), this.integrations.attachments().attachedTo(anything));
        Assertions.assertFalse(this.integrations.attachments().remove(null, anything));
    }

    @Test
    void aModulesAttachmentsAreSeenWhileItIsLoaded() {
        OneShop shop = new OneShop();
        this.integrations.addAttachments(shop);

        Assertions.assertTrue(this.integrations.attachments().isAttachment(shop.sign));
        Assertions.assertEquals(List.of(shop.sign), this.integrations.attachments().attachedTo(shop.container));

        this.integrations.removeAttachments(shop);

        Assertions.assertFalse(this.integrations.attachments().isAttachment(shop.sign));
        Assertions.assertEquals(List.of(), this.integrations.attachments().attachedTo(shop.container));
    }

    @Test
    void anAttachmentIsRemovedByTheModuleThatKnowsIt() {
        OneShop first = new OneShop();
        OneShop second = new OneShop();
        this.integrations.addAttachments(first);
        this.integrations.addAttachments(second);

        Assertions.assertTrue(this.integrations.attachments().remove(null, second.sign));

        Assertions.assertEquals(List.of(), first.removed);
        Assertions.assertEquals(List.of(second.sign), second.removed);
    }

    @Test
    void aBlockNoModuleKnowsIsNotRemoved() {
        OneShop shop = new OneShop();
        this.integrations.addAttachments(shop);

        Assertions.assertFalse(this.integrations.attachments().remove(null, block()));
        Assertions.assertEquals(List.of(), shop.removed);
    }

    @Test
    void aModuleThatFailsCountsAsKnowingNothingAndTheOthersAreStillAsked() {
        OneShop shop = new OneShop();
        this.integrations.addAttachments(new Broken());
        this.integrations.addAttachments(shop);

        Assertions.assertTrue(this.integrations.attachments().isAttachment(shop.sign));
        Assertions.assertEquals(List.of(shop.sign), this.integrations.attachments().attachedTo(shop.container));
        Assertions.assertTrue(this.integrations.attachments().remove(null, shop.sign));
        Assertions.assertFalse(this.integrations.attachments().isAttachment(block()));
    }
}
