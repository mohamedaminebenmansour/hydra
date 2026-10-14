package tn.hydra.document.service;

import org.springframework.core.io.Resource;

/** A downloadable file: the bytes resource plus metadata for response headers. */
public record DownloadFile(Resource resource, String fileName, String contentType) {}