package com.example.pdfcollector;

public record CollectorStats(
        int totalFound,
        int copiedOrMoved,
        int skipped,       // NEW
        int corrupted,
        int failed,
        long totalBytes
) { }