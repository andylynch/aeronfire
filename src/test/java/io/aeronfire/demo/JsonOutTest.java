package io.aeronfire.demo;

import io.aeronfire.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonOutTest
{
    @Test
    void roundTripsMonitorShapedState()
    {
        final String in = "{\"a\":{\"b\":[1,2.5,\"x\\\"y\",true,null]},\"n\":42}";
        assertEquals(in, JsonOut.of(Json.parse(in)));
    }

    @Test
    void wholeNumbersStayIntegers()
    {
        assertEquals("[3,12345678901]", JsonOut.of(Json.parse("[3,12345678901]")));
    }
}
