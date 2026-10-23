package io.aeronfire.posttrade;

import java.util.List;

final class PostTradeBean implements PostTradeMBean
{
    private final PostTrade postTrade;

    PostTradeBean(final PostTrade postTrade)
    {
        this.postTrade = postTrade;
    }

    @Override
    public String getSummary()
    {
        final List<PostTrade.HospitalItem> items = postTrade.hospital();
        final long open = items.stream().filter((i) -> i.open).count();
        return open + " open hospital items of " + items.size() + ", " + postTrade.keeper().size() + " records";
    }

    @Override
    public String[] getOpenHospitalItems()
    {
        return postTrade.hospital().stream().filter((i) -> i.open)
            .map((i) -> "#" + i.id + " trade " + i.tradeId + " " + i.stage + ": " + String.join("; ", i.issues))
            .toArray(String[]::new);
    }

    @Override
    public String repair(final String user, final long item, final String field, final String value, final String reason)
    {
        return postTrade.repair(user, item, field, value, reason);
    }

    @Override
    public String resubmit(final String user, final long item)
    {
        return postTrade.resubmit(user, item);
    }

    @Override
    public String verifyRecords()
    {
        final String broken = postTrade.keeper().verify();
        return null == broken ? "intact" : broken;
    }
}
