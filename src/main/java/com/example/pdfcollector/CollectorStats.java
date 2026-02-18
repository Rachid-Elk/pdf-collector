package com.example.pdfcollector;

public record CollectorStats(
        int totalFound,
        int copiedOrMoved,
        int corrupted,
        int failed,
        long totalBytes
) { }
