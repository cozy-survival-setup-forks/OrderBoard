package dev.orderboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderBoardPluginTest {

    @Test
    void parsesAmountsWithSuffixesAndCommas() {
        assertEquals(250, OrderBoardPlugin.parseAmount("250"));
        assertEquals(1500, OrderBoardPlugin.parseAmount("1.5k"));
        assertEquals(2_000_000, OrderBoardPlugin.parseAmount("2m"));
        assertEquals(1000, OrderBoardPlugin.parseAmount("1,000"));
    }

    @Test
    void rejectsInvalidAmounts() {
        assertNull(OrderBoardPlugin.parseAmount("abc"));
        assertNull(OrderBoardPlugin.parseAmount("0"));
        assertNull(OrderBoardPlugin.parseAmount("-5"));
        assertNull(OrderBoardPlugin.parseAmount("2.5"));
    }

    @Test
    void parsesAndRoundsPrices() {
        assertEquals(12.5, OrderBoardPlugin.parsePrice("12.5"));
        assertEquals(0.0, OrderBoardPlugin.parsePrice("0.001"));
        assertEquals(1000.0, OrderBoardPlugin.parsePrice("1,000"));
    }

    @Test
    void rejectsInvalidPrices() {
        assertNull(OrderBoardPlugin.parsePrice("abc"));
        assertNull(OrderBoardPlugin.parsePrice("0"));
        assertNull(OrderBoardPlugin.parsePrice("-1"));
    }
}
