# Deterministic Approach for JPEG Fragmentation Point Detection
## Major Project – Group C-20

---

## Project Overview
This project implements a **deterministic system for JPEG fragmentation, fragment boundary detection, and reconstruction**.

The system simulates real-world file fragmentation by inserting controlled noise into valid JPEG images, detects fragmented regions using **JPEG structural validation**, and reconstructs the original image by removing the detected noise segments.

The approach avoids heuristic or machine-learning methods and instead relies on **strict JPEG decoding rules**, **deterministic ordering**, and **block-aligned boundary snapping** to ensure reproducibility and explainability.

---

## Key Objectives
- Simulate JPEG fragmentation using controlled noise insertion
- Detect JPEG fragment boundaries deterministically
- Handle false positives caused by entropy conformity
- Align detected boundaries to filesystem block units
- Reconstruct the original JPEG image accurately
- Compare detected fragments with ground truth for evaluation

---

## Core Design Principles
- **Deterministic behavior** – same input always produces the same output
- **Entropy-region-only fragmentation** – header integrity is preserved
- **Block-based alignment** – all operations use 4KB blocks
- **Structural JPEG validation** – no heuristics or learning models
- **Explicit ground truth tracking** – enables accuracy measurement

---

## Workflow Summary
1. User uploads one or more JPEG images
2. System parses JPEG structure (SOI, header, entropy region, EOI)
3. Fragmentation inserts fixed-size noise blocks only into entropy data
4. Fragment boundaries and insertion points are recorded as ground truth
5. JPEG validator scans the fragmented file sequentially
6. Fragment boundaries are detected using decoding failures and recovery points
7. Detected boundaries are sorted deterministically
8. Closely spaced fragments are merged to reduce false positives
9. Boundaries are snapped to nearest 4KB block alignment
10. Invalid or too-small fragments are discarded
11. Detected fragments are compared with ground truth
12. Original image is reconstructed by concatenating valid fragments

---

## Fragmentation Model
- **Block Size:** 4096 bytes (4KB)
- **Noise Size:** 4KB per insertion
- **Fragmentation Zone:** Entropy-coded data only (after SOS marker)

This design simulates realistic file system fragmentation while maintaining JPEG decodability.

---

## Detection Methodology
- Uses standard JPEG Huffman decoding
- Detects entropy inconsistencies such as:
  - Invalid Huffman symbols
  - RLE constraint violations
  - MCU structural breaks
- Recovery points indicate possible fragment starts
- Detection operates at **byte level**, not block level

> **Note:** Random noise may temporarily satisfy JPEG entropy rules, leading to false positives.  
> This is a known and documented limitation of deterministic validation.

---

## Boundary Snapping
Detected boundaries are aligned to filesystem blocks:
- **Snap Interval:** 4096 bytes
- **Snap Tolerance:** 768 bytes
- **Minimum Fragment Size:** 4096 bytes
- **Out-of-bounds protection:** Snapped boundaries are capped to file size

Snapping improves reconstruction accuracy and reduces boundary drift.

---

## Accuracy Evaluation
Detected fragments are compared with ground truth using:
- Detection rate
- Boundary offset deltas

---

## Reconstruction
- Extracts detected fragments from fragmented JPEG
- Concatenates fragments in deterministic order
- Produces a reconstructed JPEG file

### Reconstruction Validation
- File size comparison
- Byte-level comparison (if original is available)
- Visual inspection

---

## Limitations
- Absence of Huffman violations does not guarantee semantic JPEG validity
- Random entropy may temporarily pass decoding rules
- Exact byte-level boundary detection is not always possible
- Small noise insertions may evade detection

These limitations are inherent to deterministic JPEG validation and are mitigated using snapping, merging, and minimum fragment constraints.

---

## Intended Use
- Academic research and evaluation
- Digital forensics experimentation
- Understanding deterministic JPEG carving limitations
- Fragmentation and reconstruction analysis

---

# JPEG Fragmentation Detection System

## Prerequisites
- Java 11 or higher
- Maven 3.6+
- Modern web browser (Chrome, Firefox, Edge)

---

## Installation & Setup

### 1. Clone or Navigate to Project
```
cd path/to/Major-Project/jpeg-fragments
```

2. Build the Project
```
mvn clean install
```

3. Start the Backend Server
```
mvn spring-boot:run
```

The server starts at:

http://localhost:8080

4. Open the Web Interface

Open your browser and navigate to:

http://localhost:8080/index.html

## Group Information

**Major Project**  
**Group:** C-20  
**Title:** Deterministic Approach for JPEG Fragmentation Point Detection

