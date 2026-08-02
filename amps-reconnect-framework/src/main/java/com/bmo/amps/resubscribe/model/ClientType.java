package com.bmo.amps.resubscribe.model;

/**
 * Logical identity of a configured HAClient/queue pairing. Add a constant here and a matching
 * entry under {@code amps.clients} in application.yml to add another client — no other code
 * changes are required (see ARCHITECTURE.md {@literal §}5).
 */
public enum ClientType {
    CLIENT_1,
    CLIENT_2,
    CLIENT_3
}
