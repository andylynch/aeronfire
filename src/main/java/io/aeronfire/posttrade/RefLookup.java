package io.aeronfire.posttrade;

import io.aeronfire.refdata.Model;
import io.aeronfire.refdata.RefDataStore;

/**
 * What post-trade needs from reference data. In production this is the reference data service's API.
 */
public interface RefLookup
{
    Model.Client client(int clientId);

    Model.Instrument instrument(int instrumentId);

    Model.LeiRecord lei(String lei);

    /**
     * Drop any cache so the next lookup sees reference data repairs made a moment ago.
     */
    default void refreshNow()
    {
    }

    /**
     * In-process lookup over a golden copy (tests, single-process demos).
     */
    static RefLookup of(final RefDataStore store)
    {
        return new RefLookup()
        {
            @Override
            public Model.Client client(final int clientId)
            {
                return store.client(clientId);
            }

            @Override
            public Model.Instrument instrument(final int instrumentId)
            {
                return store.instrumentById(instrumentId);
            }

            @Override
            public Model.LeiRecord lei(final String lei)
            {
                return store.lei(lei);
            }
        };
    }
}
