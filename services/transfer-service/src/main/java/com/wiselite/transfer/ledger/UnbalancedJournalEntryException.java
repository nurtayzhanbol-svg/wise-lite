package com.wiselite.transfer.ledger;

public class UnbalancedJournalEntryException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public UnbalancedJournalEntryException(String message) {
        super(message);
    }
}
