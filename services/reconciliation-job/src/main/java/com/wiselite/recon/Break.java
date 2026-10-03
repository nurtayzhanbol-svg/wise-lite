package com.wiselite.recon;

public record Break(BreakType type, BreakType.Severity severity, String reference, String details) {

    public static Break of(BreakType type, String reference, String details) {
        return new Break(type, type.severity(), reference, details);
    }
}
