package net.democracycraft.vault.internal.data;

import net.democracycraft.vault.api.data.ItemDto;
import net.democracycraft.vault.internal.util.ItemSerialization;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Objects;

/**
 * Concrete implementation of ItemDto.
 * Stores the amount and the serialized bytes of the ItemStack.
 */
public record ItemDtoImp(int amount, byte[] bytes) implements ItemDto {
    public ItemDtoImp(int amount, byte[] bytes) {
        this.amount = amount;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    public static ItemDto fromItemStack(ItemStack stack) {
        if (stack == null) return new ItemDtoImp(0, new byte[0]);
        return new ItemDtoImp(stack.getAmount(), ItemSerialization.toBytes(stack));
    }

    @Override
    public ItemStack getItemStack() {
        return ItemSerialization.fromBytes(bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemDtoImp that = (ItemDtoImp) o;
        return amount == that.amount && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(amount);
        result = 31 * result + Arrays.hashCode(bytes);
        return result;
    }
}
