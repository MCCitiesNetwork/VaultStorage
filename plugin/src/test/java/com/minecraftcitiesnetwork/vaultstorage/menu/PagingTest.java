package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    void nothingToShowIsStillOnePage() {
        Paging paging = Paging.of(0, 20, 0);
        Assertions.assertEquals(1, paging.pages());
        Assertions.assertEquals(0, paging.page());
        Assertions.assertFalse(paging.hasPrevious());
        Assertions.assertFalse(paging.hasNext());
    }

    @Test
    void aFullLastPageDoesNotAddAnEmptyOne() {
        Assertions.assertEquals(2, Paging.of(40, 20, 0).pages());
        Assertions.assertEquals(3, Paging.of(41, 20, 0).pages());
    }

    @Test
    void aPagePastTheEndIsTheLastPage() {
        Paging paging = Paging.of(41, 20, 99);
        Assertions.assertEquals(2, paging.page());
        Assertions.assertEquals(40, paging.offset());
        Assertions.assertTrue(paging.hasPrevious());
        Assertions.assertFalse(paging.hasNext());
    }

    @Test
    void aNegativePageIsTheFirst() {
        Paging paging = Paging.of(41, 20, -3);
        Assertions.assertEquals(0, paging.page());
        Assertions.assertEquals(0, paging.offset());
        Assertions.assertTrue(paging.hasNext());
    }
}
