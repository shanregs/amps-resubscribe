# Business Analyst Review Questions

## Purpose

This document contains mapping questions that require Business Analyst or business-owner confirmation before implementation.

The questions are generated from discrepancies between:

- Mapping CSV
- Actual input records
- Source-specific mappings
- Field values
- Existing application behavior

No business assumption should be made without confirmation.

---

# Question Status

Use:

```text
OPEN
BA_CONFIRMED
BA_REJECTED
DEFERRED
NOT_REQUIRED
```

---

# Priority

```text
HIGH
MEDIUM
LOW
```

---

# BA Questions

## BA-001

**Field:** TBD

**Source:** TBD

**Documented Mapping:** TBD

**Actual Input Field:** TBD

**Issue:** TBD

**Question for BA:**

> TBD

**Expected Decision:** TBD

**Priority:** HIGH / MEDIUM / LOW

**Status:** OPEN

---

## BA-002

**Field:** TBD

**Source:** TBD

**Documented Mapping:** TBD

**Actual Input Field:** TBD

**Issue:** TBD

**Question for BA:**

> TBD

**Expected Decision:** TBD

**Priority:** HIGH / MEDIUM / LOW

**Status:** OPEN

---

# Summary

| Question ID | Field | Source | Issue | Priority | Status | Decision |
|---|---|---|---|---|---|---|
| BA-001 | TBD | TBD | TBD | HIGH | OPEN | TBD |

---

# Typical Questions

The following types of questions should be raised when applicable.

### Field Name Difference

> The mapping specifies `<expected-field>`, but the actual source contains `<actual-field>`. Should the actual field be treated as the source for `<logical-field>`?

### Multiple Possible Fields

> Multiple candidate fields were identified for `<logical-field>`. Which source field should be used?

### Missing Field

> The mapping specifies `<field>`, but the field was not found in the provided input records. Is this field optional, obsolete, or expected in another message type?

### Unmapped Input Field

> The input contains `<field>`, but no mapping exists in the current mapping specification. Should this field be mapped?

### Field Value Difference

> The mapping specifies the expected values as `<values>`, but the actual input contains `<actual-values>`. Should the additional values be supported?

### Mapping Status Difference

> The CSV marks `<field>` as `<status>`, but the actual validation indicates `<actual-status>`. Which status should be considered correct?

### Code Behavior Difference

> The current implementation handles `<field>` differently from the documented mapping. Should the existing behavior be retained or changed?

---

# BA Approval Rule

Do not proceed to implementation for business-impacting discrepancies until the corresponding BA question has been resolved.