package com.mystock.ingest;

/** The API key for a provider is not configured. Maps to protocol code UPSTREAM. */
public class NotConfiguredException extends UpstreamException {
    public NotConfiguredException(String envVar) { super(envVar + " not configured"); }
}
