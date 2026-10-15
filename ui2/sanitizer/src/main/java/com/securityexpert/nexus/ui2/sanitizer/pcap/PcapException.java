package com.securityexpert.nexus.ui2.sanitizer.pcap;

/** A capture the pcap path refuses; the message is fixed text (PacketBreaker's wording), never capture content. */
public final class PcapException extends RuntimeException {
    public PcapException(String message) {
        super(message);
    }
}
