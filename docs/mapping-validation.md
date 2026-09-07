# Mapping Validation Report

## 1. Purpose

This document records the cross-validation between:

1. The documented mapping CSV.
2. Actual input/source message files.
3. Existing application code.

The objective is to determine whether every documented mapping is actually supported by the input data and existing implementation.

---

# 2. Mapping CSV Columns

The mapping specification contains:

| Column | Description |
|---|---|
| Name | Logical field name |
| ICI | ICI mapping |
| SFCM | SFCM mapping |
| GMTC | GMTC mapping |
| YAMP | YAMP mapping |
| Field Values | Expected field values/details |
| Mapping Status | Documented mapping status |

---

# 3. Validation Rules

Each field must be checked against:

```text
Name
  ↓
ICI / SFCM / GMTC / YAMP
  ↓
Actual Input Files
  ↓
Existing Java Code
```

---

# 4. Validation Status

| Status | Meaning |
|---|---|
| MAPPED | Mapping exists and actual input field is confirmed |
| UNMAPPED | No source mapping is documented |
| MAPPING_NOT_FOUND_IN_INPUT | Mapping exists but field was not found in actual input |
| INPUT_FIELD_NOT_DOCUMENTED | Actual input field exists but is not documented |
| POTENTIAL_MISMATCH | Similar field exists but exact mapping is not confirmed |
| VALUE_MISMATCH | Actual values differ from documented values |
| DOCUMENTATION_STATUS_INCONSISTENCY | Mapping Status conflicts with validation |
| REQUIRES_REVIEW | Evidence is insufficient |
| NOT_APPLICABLE | Mapping is explicitly not applicable |

---

# 5. Overall Summary

| Metric | Count |
|---|---:|
| Total Mapping Rows | TBD |
| ICI Mappings | TBD |
| SFCM Mappings | TBD |
| GMTC Mappings | TBD |
| YAMP Mappings | TBD |
| Correctly Mapped | TBD |
| Unmapped | TBD |
| Mapping Not Found | TBD |
| Input Fields Not Documented | TBD |
| Potential Mismatches | TBD |
| Value Mismatches | TBD |
| Documentation Inconsistencies | TBD |
| BA Review Required | TBD |
| Code Changes Potentially Required | TBD |

---

# 6. Detailed Validation

| Name | ICI | SFCM | GMTC | YAMP | Field Values | CSV Status | Actual Source | Actual Field | Found | Code Handling | Validation Status | Remarks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

---

# 7. Correctly Mapped Fields

List fields where the documented mapping is confirmed by actual input evidence.

| Name | Source | Mapped Field | Actual File | Evidence | Code Handling |
|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD |

---

# 8. Missing / Unmapped Fields

| Name | Source | Mapping CSV Value | Actual Input | Status | Remarks |
|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD |

---

# 9. Input Fields Not Documented

Fields found in actual input files but not represented in the mapping CSV.

| Actual Field | Source File | Sample Value | Mapping Found? | BA Review Required |
|---|---|---|---|---|
| TBD | TBD | TBD | NO | YES |

---

# 10. Potential Field Mismatches

| Logical Field | Expected Field | Actual Field | Source | Confidence | BA Decision Required |
|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | YES |

---

# 11. Field Value Validation

Compare the `Field Values` column against actual values.

| Name | Expected Values | Actual Values | Match | Remarks |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD |

---

# 12. Mapping Status Validation

Compare the CSV `Mapping Status` with actual validation results.

| Name | CSV Mapping Status | Actual Validation | Consistent? | Remarks |
|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD |

---

# 13. Existing Code Impact

For each field requiring investigation:

| Name | Parser | DTO/Model | Transformation | Business Logic | Persistence/Output | Code Change Required |
|---|---|---|---|---|---|---|
| TBD | TBD | TBD | TBD | TBD | TBD | TBD |

---

# 14. High Priority Issues

List issues that could affect:

- Regulatory reporting
- Trade processing
- Financial calculations
- Mandatory fields
- Downstream interfaces
- Data integrity

| Issue | Field | Impact | Priority | BA Required |
|---|---|---|---|---|
| TBD | TBD | TBD | HIGH | YES |

---

# 15. Conclusion

The mapping is considered validated only when the documented mapping is supported by actual input evidence and, where applicable, existing application code.

Any uncertain mapping must be classified as:

```text
REQUIRES_REVIEW
```

and should not be implemented automatically.