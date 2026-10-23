package io.aeronfire.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionsTest
{
    @Test
    void clientBuyMakesUsShort()
    {
        final Positions positions = new Positions();
        positions.applyFill(2, true, 5_000_000L);
        positions.applyFill(2, false, 2_000_000L);

        assertEquals(-3_000_000L, positions.net(2));
        assertEquals(1, positions.nonZeroCount());
    }
}
