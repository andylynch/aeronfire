package io.aeronfire.gateway;

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.fields.DecimalFloat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixGatewayTest
{
    @Test
    void wholeUnitsDropsTheFractionalScale()
    {
        assertEquals(5_000_000L, FixGateway.wholeUnits(new DecimalFloat(5_000_000L, 0)));
        assertEquals(5_000_000L, FixGateway.wholeUnits(new DecimalFloat(500_000_000L, 2)));
        assertEquals(1L, FixGateway.wholeUnits(new DecimalFloat(15L, 1)));
    }

    @Test
    void unparseableIdsFallBack()
    {
        assertEquals(7, FixGateway.parseIntOr("7", -1));
        assertEquals(-1, FixGateway.parseIntOr("CLIENT-7", -1));
        assertEquals(-1L, FixGateway.parseLongOr("", -1));
    }
}
