package io.aeronfire.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Non-blocking shutdown signal for duty-cycle loops.
 * <p>
 * On SIGINT/SIGTERM the JVM runs the hook, which raises the flag and then waits (bounded) for the main
 * thread to call {@link #closed()}, so try-with-resources blocks get to close Aeron and Artio cleanly.
 */
public final class ShutdownFlag
{
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean running = true;

    public static ShutdownFlag register()
    {
        final ShutdownFlag flag = new ShutdownFlag();
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            flag.running = false;
            try
            {
                flag.closed.await(5, TimeUnit.SECONDS);
            }
            catch (final InterruptedException ex)
            {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-flag"));
        return flag;
    }

    public boolean isRunning()
    {
        return running;
    }

    public void stop()
    {
        running = false;
    }

    /**
     * Call once resources are closed, so the shutdown hook can let the JVM exit.
     */
    public void closed()
    {
        closed.countDown();
    }
}
