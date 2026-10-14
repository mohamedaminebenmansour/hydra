package tn.hydra.document.domain;

/** Lifecycle status of a document (its latest revision is the current one). */
public enum DocStatus {
    DRAFT, IN_REVIEW, APPROVED, REJECTED, SUPERSEDED, ARCHIVED
}