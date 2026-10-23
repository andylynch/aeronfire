package io.aeronfire.domain;

/**
 * Fine-grained entitlements for control-plane users, enforced inside the cluster.
 * <p>
 * Each user has:
 * <ul>
 *   <li><b>Permissions</b>: one bit per kind of action.</li>
 *   <li><b>Scope</b>: which clients and which instruments they may act on.</li>
 *   <li><b>Limits</b>: the largest credit limit, position adjustment and inventory limit they may set alone.
 *       Anything bigger becomes a pending change that a different user must approve (four-eyes).</li>
 * </ul>
 * Living in the cluster makes entitlements replicated, deterministic and journalled with every decision.
 * Users are numeric ids here; the control plane maps authenticated principals to them.
 */
public final class Entitlements
{
    public static final int CAPACITY = 64;
    private static final int SCOPE_WORDS = CreditBook.CAPACITY / 64;

    public static final int CREDIT = 1;
    public static final int INVENTORY = 1 << 1;
    public static final int POSITION_ADJUST = 1 << 2;
    public static final int ALGO = 1 << 3;
    public static final int KILL_SWITCH = 1 << 4;
    public static final int ENTITLEMENTS = 1 << 5;
    public static final int APPROVE = 1 << 6;
    public static final int HOSPITAL = 1 << 7;
    public static final int REPLAY = 1 << 8;
    public static final int VIEW = 1 << 9;
    public static final int REFDATA = 1 << 10;
    public static final int ALL = (1 << 11) - 1;

    public static final int ALL_INSTRUMENTS = (1 << Instruments.COUNT) - 1;

    /**
     * Built-in bootstrap user: every permission, unlimited. Its changes still go through the journal.
     */
    public static final int ROOT = 0;

    private final boolean[] known = new boolean[CAPACITY];
    private final int[] permissions = new int[CAPACITY];
    private final boolean[] allClients = new boolean[CAPACITY];
    private final long[] clientScope = new long[CAPACITY * SCOPE_WORDS];
    private final int[] instrumentScope = new int[CAPACITY];
    private final long[] maxCreditLimit = new long[CAPACITY];
    private final long[] maxAdjust = new long[CAPACITY];
    private final long[] maxInventoryLimit = new long[CAPACITY];

    /**
     * Demo users, identical on every member. The control plane names them root, risk, ops, deskhead,
     * compliance, sales and refdata (the reference data service's system user).
     */
    public static Entitlements withDemoUsers()
    {
        final Entitlements e = new Entitlements();
        e.set(ROOT, ALL, true, ALL_INSTRUMENTS, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        e.set(1, CREDIT | INVENTORY | ALGO | KILL_SWITCH | ENTITLEMENTS | APPROVE | VIEW, true, ALL_INSTRUMENTS,
            250_000_000L, 0, 150_000_000L);
        e.set(2, POSITION_ADJUST | HOSPITAL | REPLAY | VIEW, true, ALL_INSTRUMENTS, 0, 10_000_000L, 0);
        e.set(3, INVENTORY | POSITION_ADJUST | APPROVE | VIEW, true, 0b10011, 0, 50_000_000L, 200_000_000L);
        e.set(4, KILL_SWITCH | APPROVE | ENTITLEMENTS | REFDATA | VIEW, true, ALL_INSTRUMENTS, 0, 0, 0);
        e.set(5, CREDIT | VIEW, false, ALL_INSTRUMENTS, 75_000_000L, 0, 0);
        for (int client = 6; client <= 9; client++)
        {
            e.addClient(5, client);
        }
        e.set(6, REFDATA, true, 0, 0, 0, 0);
        return e;
    }

    public static boolean isValidUser(final int userId)
    {
        return userId >= 0 && userId < CAPACITY;
    }

    public void set(
        final int userId,
        final int userPermissions,
        final boolean userAllClients,
        final int userInstrumentScope,
        final long userMaxCreditLimit,
        final long userMaxAdjust,
        final long userMaxInventoryLimit)
    {
        known[userId] = true;
        permissions[userId] = userPermissions;
        allClients[userId] = userAllClients;
        instrumentScope[userId] = userInstrumentScope;
        maxCreditLimit[userId] = userMaxCreditLimit;
        maxAdjust[userId] = userMaxAdjust;
        maxInventoryLimit[userId] = userMaxInventoryLimit;
        java.util.Arrays.fill(clientScope, userId * SCOPE_WORDS, (userId + 1) * SCOPE_WORDS, 0L);
    }

    public void addClient(final int userId, final int clientId)
    {
        if (CreditBook.isValidId(clientId))
        {
            clientScope[userId * SCOPE_WORDS + (clientId >>> 6)] |= 1L << (clientId & 63);
        }
    }

    public void clear()
    {
        java.util.Arrays.fill(known, false);
    }

    public boolean isKnown(final int userId)
    {
        return isValidUser(userId) && known[userId];
    }

    public boolean has(final int userId, final int permission)
    {
        return isKnown(userId) && (permissions[userId] & permission) == permission;
    }

    /**
     * @param clientId a client, or -1 for "every client", which needs an all-clients scope.
     */
    public boolean clientInScope(final int userId, final int clientId)
    {
        if (!isKnown(userId))
        {
            return false;
        }
        if (allClients[userId])
        {
            return true;
        }
        return CreditBook.isValidId(clientId) &&
            (clientScope[userId * SCOPE_WORDS + (clientId >>> 6)] & (1L << (clientId & 63))) != 0;
    }

    public boolean instrumentInScope(final int userId, final int instrumentId)
    {
        return isKnown(userId) && Instruments.isValid(instrumentId) &&
            (instrumentScope[userId] & (1 << instrumentId)) != 0;
    }

    public int permissions(final int userId)
    {
        return permissions[userId];
    }

    public boolean allClients(final int userId)
    {
        return allClients[userId];
    }

    public int instrumentScope(final int userId)
    {
        return instrumentScope[userId];
    }

    public long maxCreditLimit(final int userId)
    {
        return maxCreditLimit[userId];
    }

    public long maxAdjust(final int userId)
    {
        return maxAdjust[userId];
    }

    public long maxInventoryLimit(final int userId)
    {
        return maxInventoryLimit[userId];
    }

    /**
     * Number of individually scoped clients (ignores the all-clients flag).
     */
    public int clientCount(final int userId)
    {
        int count = 0;
        for (int w = 0; w < SCOPE_WORDS; w++)
        {
            count += Long.bitCount(clientScope[userId * SCOPE_WORDS + w]);
        }
        return count;
    }

    /**
     * The n-th individually scoped client, in ascending id order, or -1.
     */
    public int clientAt(final int userId, final int index)
    {
        int seen = 0;
        for (int clientId = 0; clientId < CreditBook.CAPACITY; clientId++)
        {
            if ((clientScope[userId * SCOPE_WORDS + (clientId >>> 6)] & (1L << (clientId & 63))) != 0 && seen++ == index)
            {
                return clientId;
            }
        }
        return -1;
    }
}
