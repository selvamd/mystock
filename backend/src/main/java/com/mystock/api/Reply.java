package com.mystock.api;

/** Result of an op: a JSON value, an Arrow stream body, or nothing. */
public final class Reply {
    public final Object json;
    public final Arrow.Body arrow;

    private Reply(Object json, Arrow.Body arrow) { this.json = json; this.arrow = arrow; }

    public static Reply json(Object o) { return new Reply(o, null); }
    public static Reply arrow(Arrow.Body b) { return new Reply(null, b); }
    public static Reply none() { return new Reply(null, null); }
}
