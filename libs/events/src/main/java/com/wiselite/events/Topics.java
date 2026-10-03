package com.wiselite.events;

/** Topic names are versioned: a breaking schema change gets a new topic, not a silent edit. */
public final class Topics {

    public static final String TRANSFER_EVENTS = "transfers.events.v1";
    public static final String PAYOUT_EVENTS = "payouts.events.v1";

    /** Header names set by the outbox relay on every record. */
    public static final String HEADER_EVENT_ID = "event-id";
    public static final String HEADER_EVENT_TYPE = "event-type";

    private Topics() {}
}
