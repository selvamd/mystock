package com.mystock.legacy;

/** ADDED: exposes the StockFinDB adapter's legacy column -> Alpha Vantage field mapping. */
public final class LegacyNames {
    private LegacyNames() {}
    public static String camel(String legacyColumn) { return StockFinDB.field(legacyColumn); }
}
