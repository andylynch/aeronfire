package io.aeronfire.domain;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Changes awaiting a second user's approval (four-eyes).
 * <p>
 * Each entry keeps the original command message verbatim. Approval re-applies those exact bytes, checked
 * against the approver's permissions and scope, so what gets approved is precisely what was requested.
 */
public final class PendingChanges
{
    public static final int CAPACITY = 64;
    public static final int MAX_COMMAND_LENGTH = 256;

    private final long[] pendingId = new long[CAPACITY];
    private final int[] requestedBy = new int[CAPACITY];
    private final int[] templateId = new int[CAPACITY];
    private final long[] requestedAt = new long[CAPACITY];
    private final int[] length = new int[CAPACITY];
    private final byte[] command = new byte[CAPACITY * MAX_COMMAND_LENGTH];

    public PendingChanges()
    {
        java.util.Arrays.fill(pendingId, -1);
    }

    /**
     * @return the slot used, or -1 if full or the command is too long.
     */
    public int add(
        final long id,
        final int requester,
        final int template,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int commandLength)
    {
        if (commandLength > MAX_COMMAND_LENGTH)
        {
            return -1;
        }
        for (int slot = 0; slot < CAPACITY; slot++)
        {
            if (pendingId[slot] < 0)
            {
                pendingId[slot] = id;
                requestedBy[slot] = requester;
                templateId[slot] = template;
                requestedAt[slot] = timestamp;
                length[slot] = commandLength;
                buffer.getBytes(offset, command, slot * MAX_COMMAND_LENGTH, commandLength);
                return slot;
            }
        }
        return -1;
    }

    public int find(final long id)
    {
        for (int slot = 0; slot < CAPACITY; slot++)
        {
            if (pendingId[slot] == id)
            {
                return slot;
            }
        }
        return -1;
    }

    public void remove(final int slot)
    {
        pendingId[slot] = -1;
    }

    /**
     * Copy a pending command into {@code dst} at offset 0.
     *
     * @return its length.
     */
    public int copyCommand(final int slot, final MutableDirectBuffer dst)
    {
        dst.putBytes(0, command, slot * MAX_COMMAND_LENGTH, length[slot]);
        return length[slot];
    }

    public long pendingIdAt(final int slot)
    {
        return pendingId[slot];
    }

    public int requestedByAt(final int slot)
    {
        return requestedBy[slot];
    }

    public int templateIdAt(final int slot)
    {
        return templateId[slot];
    }

    public long requestedAtAt(final int slot)
    {
        return requestedAt[slot];
    }

    public int lengthAt(final int slot)
    {
        return length[slot];
    }

    public byte[] commandBytes()
    {
        return command;
    }
}
