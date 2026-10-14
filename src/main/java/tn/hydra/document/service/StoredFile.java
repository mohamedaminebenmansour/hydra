package tn.hydra.document.service;

/** Result of storing a file: where it lives, how big, and its checksum. */
public record StoredFile(String key, long size, String checksumSha256) {}