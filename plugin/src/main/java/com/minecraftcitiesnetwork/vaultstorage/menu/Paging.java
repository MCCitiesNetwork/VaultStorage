package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.jetbrains.annotations.NotNull;
/**
 * Which page of a list is on screen. Built from the total and the page asked for, which is
 * clamped: a list can shrink between two clicks.
 *
 * @param page zero-based
 */
public record Paging(int total, int perPage, int page, int pages) {

    public static @NotNull Paging of(int total, int perPage, int requestedPage) {
        int size = Math.max(1, perPage);
        int pages = Math.max(1, (Math.max(0, total) + size - 1) / size);
        int page = Math.min(Math.max(0, requestedPage), pages - 1);
        return new Paging(Math.max(0, total), size, page, pages);
    }

    /** The index of the first entry on this page. */
    public int offset() {
        return this.page * this.perPage;
    }

    public boolean hasPrevious() {
        return this.page > 0;
    }

    public boolean hasNext() {
        return this.page < this.pages - 1;
    }
}
