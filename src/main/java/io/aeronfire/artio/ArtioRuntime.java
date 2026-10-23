package io.aeronfire.artio;

import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.IoUtil;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.io.File;

/**
 * One process's Artio stack: media driver plus archive (Artio journals FIX messages to it for replay),
 * the FIX engine, and a library connection to it.
 * <p>
 * Each process gets its own Aeron directory and an IPC-only archive control channel, so a gateway,
 * a venue simulator and three cluster members can share a host without port clashes.
 */
public final class ArtioRuntime implements AutoCloseable
{
    private final ArchivingMediaDriver driver;
    private final FixEngine engine;
    private final String aeronDirectoryName;

    private ArtioRuntime(final ArchivingMediaDriver driver, final FixEngine engine, final String aeronDirectoryName)
    {
        this.driver = driver;
        this.engine = engine;
        this.aeronDirectoryName = aeronDirectoryName;
    }

    /**
     * @param name     short process name, used for the Aeron and log directories.
     * @param workDir  where FIX journals and the archive go.
     * @param customise hook to set acceptor or authentication options on the engine configuration.
     */
    public static ArtioRuntime launch(
        final String name, final File workDir, final java.util.function.Consumer<EngineConfiguration> customise)
    {
        final String aeronDir = CommonContext.getAeronDirectoryName() + "-" + name;
        final File logDir = new File(workDir, name + "-fix-logs");
        final File archiveDir = new File(workDir, name + "-archive");
        IoUtil.delete(logDir, true);

        final MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);

        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDir)
            .archiveDirectoryName(archiveDir.getAbsolutePath())
            .deleteArchiveOnStart(true)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .controlChannelEnabled(false)
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .recordingEventsEnabled(false)
            // Used only for archive replication, which we don't do; must still be set when control is IPC-only.
            .archiveClientContext(new AeronArchive.Context().controlResponseChannel("aeron:udp?endpoint=localhost:0"));

        final ArchivingMediaDriver driver = ArchivingMediaDriver.launch(driverContext, archiveContext);

        final EngineConfiguration configuration = new EngineConfiguration()
            .libraryAeronChannel(CommonContext.IPC_CHANNEL)
            .logFileDir(logDir.getAbsolutePath())
            .deleteLogFileDirOnStart(true);
        configuration.aeronContext().aeronDirectoryName(aeronDir);
        configuration.aeronArchiveContext()
            .aeronDirectoryName(aeronDir)
            .controlRequestChannel(AeronArchive.Configuration.localControlChannel())
            .controlResponseChannel(CommonContext.IPC_CHANNEL);
        customise.accept(configuration);

        final FixEngine engine;
        try
        {
            engine = FixEngine.launch(configuration);
        }
        catch (final RuntimeException ex)
        {
            driver.close();
            throw ex;
        }

        return new ArtioRuntime(driver, engine, aeronDir);
    }

    public FixLibrary connectLibrary(final LibraryConfiguration configuration)
    {
        configuration.libraryAeronChannels(java.util.List.of(CommonContext.IPC_CHANNEL));
        configuration.aeronContext().aeronDirectoryName(aeronDirectoryName);
        return FixLibrary.connect(configuration);
    }

    public String aeronDirectoryName()
    {
        return aeronDirectoryName;
    }

    @Override
    public void close()
    {
        engine.close();
        driver.close();
    }
}
