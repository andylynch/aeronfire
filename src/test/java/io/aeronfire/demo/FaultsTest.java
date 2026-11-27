package io.aeronfire.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FaultsTest
{
    private static Faults faults(final DockerApi docker)
    {
        return new Faults(docker, List.of("node0", "node1", "node2"), new Timeline(), (nodeId, op) -> { }, "eth0");
    }

    @Test
    void rejectsUnknownNodeBeforeTouchingDocker()
    {
        assertThrows(IllegalArgumentException.class, () -> faults(null).apply("gateway", "kill", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> faults(null).apply("node0; rm", "kill", 0, 0, 0));
    }

    @Test
    void withoutDockerFaultsAreOffRatherThanCrashing()
    {
        assertThrows(IOException.class, () -> faults(null).apply("node1", "kill", 0, 0, 0));
    }

    @Test
    void refusesOddInterfaceNames()
    {
        assertThrows(IllegalArgumentException.class, () -> new Faults(
            null, List.of("node0"), new Timeline(), (nodeId, op) -> { }, "eth0; reboot"));
    }
}
