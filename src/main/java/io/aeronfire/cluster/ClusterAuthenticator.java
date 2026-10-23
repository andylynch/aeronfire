package io.aeronfire.cluster;

import io.aeron.security.Authenticator;
import io.aeron.security.CredentialsSupplier;
import io.aeron.security.SessionProxy;
import org.agrona.collections.Long2ObjectHashMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Session authentication at the cluster's edge, run by the consensus module before a session exists.
 * <p>
 * Two kinds of session:
 * <ul>
 *   <li><b>control</b>: presents {@code control:<secret>}; may send admin commands (still subject to per-user
 *       entitlements inside the service).</li>
 *   <li><b>trading</b>: presents no credentials (gateways, monitors, post-trade, load clients); may trade,
 *       subscribe and pull the event feed, but admin commands are rejected.</li>
 * </ul>
 * Wrong control credentials are refused outright. The secret comes from {@code CONTROL_SECRET}; a real
 * deployment would use mTLS or a challenge-response against a vault, behind the same interface.
 */
public final class ClusterAuthenticator implements Authenticator
{
    public static final String CONTROL_PRINCIPAL = "control";
    public static final String TRADING_PRINCIPAL = "trading";
    public static final String DEFAULT_SECRET = "dev-control-secret";

    private static final byte[] CONTROL = CONTROL_PRINCIPAL.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRADING = TRADING_PRINCIPAL.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REJECTED = new byte[0];

    private final byte[] expectedControlCredentials;
    private final Long2ObjectHashMap<byte[]> principalBySession = new Long2ObjectHashMap<>();

    public ClusterAuthenticator(final String controlSecret)
    {
        expectedControlCredentials = controlCredentials(controlSecret);
    }

    public static byte[] controlCredentials(final String secret)
    {
        return (CONTROL_PRINCIPAL + ":" + secret).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Credentials for a control-plane cluster client.
     */
    public static CredentialsSupplier controlCredentialsSupplier(final String secret)
    {
        final byte[] credentials = controlCredentials(secret);
        return new CredentialsSupplier()
        {
            @Override
            public byte[] encodedCredentials()
            {
                return credentials;
            }

            @Override
            public byte[] onChallenge(final byte[] encodedChallenge)
            {
                return credentials;
            }
        };
    }

    @Override
    public void onConnectRequest(final long sessionId, final byte[] encodedCredentials, final long nowMs)
    {
        final byte[] principal;
        if (null == encodedCredentials || encodedCredentials.length == 0)
        {
            principal = TRADING;
        }
        else if (MessageDigest.isEqual(encodedCredentials, expectedControlCredentials)) // constant time
        {
            principal = CONTROL;
        }
        else
        {
            principal = REJECTED;
        }
        principalBySession.put(sessionId, principal);
    }

    @Override
    public void onChallengeResponse(final long sessionId, final byte[] encodedCredentials, final long nowMs)
    {
        onConnectRequest(sessionId, encodedCredentials, nowMs);
    }

    @Override
    public void onConnectedSession(final SessionProxy sessionProxy, final long nowMs)
    {
        decide(sessionProxy);
    }

    @Override
    public void onChallengedSession(final SessionProxy sessionProxy, final long nowMs)
    {
        decide(sessionProxy);
    }

    private void decide(final SessionProxy sessionProxy)
    {
        final byte[] principal = principalBySession.remove(sessionProxy.sessionId());
        if (null == principal || principal == REJECTED)
        {
            sessionProxy.reject();
        }
        else
        {
            sessionProxy.authenticate(principal);
        }
    }
}
