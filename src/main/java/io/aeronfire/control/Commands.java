package io.aeronfire.control;

import io.aeronfire.sbe.AdjustReason;
import io.aeronfire.sbe.AlgoControlsDecoder;
import io.aeronfire.sbe.AlgoControlsEncoder;
import io.aeronfire.sbe.ApprovalDecoder;
import io.aeronfire.sbe.ApprovalEncoder;
import io.aeronfire.sbe.BooleanType;
import io.aeronfire.sbe.ClientEligibilityDecoder;
import io.aeronfire.sbe.ClientEligibilityEncoder;
import io.aeronfire.sbe.CreditResetDecoder;
import io.aeronfire.sbe.CreditResetEncoder;
import io.aeronfire.sbe.CreditUpdateDecoder;
import io.aeronfire.sbe.CreditUpdateEncoder;
import io.aeronfire.sbe.InventoryLimitDecoder;
import io.aeronfire.sbe.InventoryLimitEncoder;
import io.aeronfire.sbe.MessageHeaderEncoder;
import io.aeronfire.sbe.PositionAdjustDecoder;
import io.aeronfire.sbe.PositionAdjustEncoder;
import io.aeronfire.sbe.SetEntitlementDecoder;
import io.aeronfire.sbe.SetEntitlementEncoder;
import io.aeronfire.sbe.Tier;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.Arrays;

/**
 * Encodes admin commands. The control plane is the edge, not the hot path, so each command gets its own
 * small buffer; correctness and readability win here.
 */
public final class Commands
{
    private Commands()
    {
    }

    public static byte[] creditUpdate(
        final int user, final int clientId, final int tier, final long limit, final boolean resetUtilisation)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final CreditUpdateEncoder e = new CreditUpdateEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .clientId(clientId)
            .tier(Tier.get((short)(tier - 1)))
            .creditLimit(limit)
            .resetUtilisation(resetUtilisation ? BooleanType.TRUE : BooleanType.FALSE)
            .userId(user);
        return bytes(buffer, e.encodedLength());
    }

    public static byte[] creditReset(final int user, final int clientId)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final CreditResetEncoder e = new CreditResetEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .clientId(clientId)
            .userId(user);
        return bytes(buffer, e.encodedLength());
    }

    public static byte[] inventoryLimit(
        final int user, final int instrumentId, final long maxLong, final long maxShort, final boolean halted)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final InventoryLimitEncoder e = new InventoryLimitEncoder()
            .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .instrumentId(instrumentId)
            .maxLong(maxLong)
            .maxShort(maxShort)
            .halted(halted ? BooleanType.TRUE : BooleanType.FALSE)
            .userId(user);
        return bytes(buffer, e.encodedLength());
    }

    public static byte[] positionAdjust(final int user, final int instrumentId, final long delta, final AdjustReason reason)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final PositionAdjustEncoder e = new PositionAdjustEncoder()
            .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .instrumentId(instrumentId)
            .delta(delta)
            .reason(reason)
            .userId(user);
        return bytes(buffer, e.encodedLength());
    }

    public static byte[] algoControls(
        final int user, final boolean killSwitch, final int collarBps, final int maxRfqsPerSecond)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final AlgoControlsEncoder e = new AlgoControlsEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .killSwitch(killSwitch ? BooleanType.TRUE : BooleanType.FALSE)
            .priceCollarBps(collarBps)
            .maxRfqsPerSecond(maxRfqsPerSecond)
            .userId(user);
        return bytes(buffer, e.encodedLength());
    }

    public static byte[] setEntitlement(
        final int user,
        final int target,
        final int permissions,
        final boolean allClients,
        final int[] clients,
        final int instrumentScope,
        final long maxCreditLimit,
        final long maxAdjust,
        final long maxInventoryLimit)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final SetEntitlementEncoder e = new SetEntitlementEncoder()
            .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .userId(user)
            .targetUserId(target)
            .permissions(permissions)
            .allClients(allClients ? BooleanType.TRUE : BooleanType.FALSE)
            .instrumentScope(instrumentScope)
            .maxCreditLimit(maxCreditLimit)
            .maxAdjust(maxAdjust)
            .maxInventoryLimit(maxInventoryLimit);
        final SetEntitlementEncoder.ClientsEncoder group = e.clientsCount(Math.min(clients.length, 30));
        for (int i = 0; i < Math.min(clients.length, 30); i++)
        {
            group.next().clientId(clients[i]);
        }
        return bytes(buffer, e.encodedLength());
    }

    public static byte[] approval(final int user, final long pendingId, final boolean approve)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final ApprovalEncoder e = new ApprovalEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .userId(user)
            .pendingId(pendingId)
            .approve(approve ? BooleanType.TRUE : BooleanType.FALSE);
        return bytes(buffer, e.encodedLength());
    }

    public static byte[] eligibility(final int user, final int clientId, final boolean kycValid, final boolean sanctioned)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final ClientEligibilityEncoder e = new ClientEligibilityEncoder()
            .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
            .userId(user)
            .clientId(clientId)
            .kycValid(kycValid ? BooleanType.TRUE : BooleanType.FALSE)
            .sanctioned(sanctioned ? BooleanType.TRUE : BooleanType.FALSE);
        return bytes(buffer, e.encodedLength());
    }

    public static String describeTemplate(final int templateId)
    {
        return switch (templateId)
        {
            case CreditUpdateDecoder.TEMPLATE_ID -> "credit line change";
            case CreditResetDecoder.TEMPLATE_ID -> "credit reset";
            case InventoryLimitDecoder.TEMPLATE_ID -> "inventory limit change";
            case PositionAdjustDecoder.TEMPLATE_ID -> "position adjustment";
            case AlgoControlsDecoder.TEMPLATE_ID -> "algo controls / kill switch";
            case SetEntitlementDecoder.TEMPLATE_ID -> "entitlement grant";
            case ApprovalDecoder.TEMPLATE_ID -> "approval";
            case ClientEligibilityDecoder.TEMPLATE_ID -> "client eligibility (KYC/sanctions)";
            default -> "template " + templateId;
        };
    }

    private static byte[] bytes(final UnsafeBuffer buffer, final int bodyLength)
    {
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + bodyLength);
    }
}
