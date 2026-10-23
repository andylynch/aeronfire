package io.aeronfire.bench;

import io.aeronfire.domain.Instruments;
import io.aeronfire.domain.Pricer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The pricer is on the hot path of every RFQ: one quote spread, one price, and (on trade) the risk numbers.
 * Inputs rotate through instruments, sizes and positions so the JIT cannot fold the work away.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"})
public class PricerBenchmark
{
    private static final int MASK = 1023;

    private final Pricer pricer = Pricer.defaults();
    private final int[] instrument = new int[MASK + 1];
    private final int[] spread = new int[MASK + 1];
    private final long[] notional = new long[MASK + 1];
    private final long[] position = new long[MASK + 1];
    private int i;

    public PricerBenchmark()
    {
        for (int k = 0; k <= MASK; k++)
        {
            instrument[k] = k % Instruments.COUNT;
            spread[k] = 200 + (k * 37) % 600;
            notional[k] = 1_000_000L * (1 + k % 20);
            position[k] = ((k * 7919L) % 41 - 20) * 1_000_000L;
        }
    }

    @Benchmark
    public int quotedSpreadBps()
    {
        final int k = i++ & MASK;
        return pricer.quotedSpreadBps(instrument[k], (k & 1) == 0, notional[k], position[k]);
    }

    @Benchmark
    public double price()
    {
        final int k = i++ & MASK;
        return pricer.price(instrument[k], spread[k]);
    }

    @Benchmark
    public long priceE6()
    {
        final int k = i++ & MASK;
        return pricer.priceE6(instrument[k], spread[k]);
    }

    @Benchmark
    public double cs01()
    {
        final int k = i++ & MASK;
        return pricer.cs01(instrument[k], spread[k], position[k]);
    }

    @Benchmark
    public double dv01()
    {
        final int k = i++ & MASK;
        return pricer.dv01(instrument[k], spread[k], position[k]);
    }

    @Benchmark
    public double mark()
    {
        final int k = i++ & MASK;
        return pricer.mark(instrument[k], position[k]);
    }

    /**
     * What one RFQ costs in the pricer: spread, price, and the risk numbers the trade will publish.
     */
    @Benchmark
    public double quoteAndRisk()
    {
        final int k = i++ & MASK;
        final int bps = pricer.quotedSpreadBps(instrument[k], (k & 1) == 0, notional[k], position[k]);
        return pricer.priceE6(instrument[k], bps) + pricer.cs01(instrument[k], bps, position[k]) +
            pricer.dv01(instrument[k], bps, position[k]);
    }
}
