package com.eop.audit;

/**
 * Result of recomputing the audit hash chain (contract {@code AuditVerifyResult}). {@code checkedThrough} is
 * the highest seq verified intact; {@code brokenAt} is the first seq where linkage or content fails, or null
 * when the whole chain is valid.
 */
public record AuditVerifyResult(boolean valid, long checkedThrough, Long brokenAt) {}
