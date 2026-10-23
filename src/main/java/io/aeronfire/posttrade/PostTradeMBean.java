package io.aeronfire.posttrade;

/**
 * Post-trade operations over JMX (and so Jolokia). The acting user is the first argument, as in the control
 * plane's MBeans; entitlements (HOSPITAL) are checked inside {@link PostTrade}.
 */
public interface PostTradeMBean
{
    /**
     * @return counts of open hospital items, trades and records.
     */
    String getSummary();

    /**
     * @return one line per open hospital item.
     */
    String[] getOpenHospitalItems();

    String repair(String user, long item, String field, String value, String reason);

    String resubmit(String user, long item);

    /**
     * @return "intact" or a description of the first break in the record chain.
     */
    String verifyRecords();
}
