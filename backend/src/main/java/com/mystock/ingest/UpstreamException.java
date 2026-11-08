package com.mystock.ingest;

/** A third-party data provider failed (maps to protocol code UPSTREAM). */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String msg) { super(msg); }
}
