package com.example.pdfcollector;


import java.util.Map;

public record ScanResult(
        int totalFiles,
        long totalBytes,
        Map<String, Integer> countByExt,
        Map<String, Long> bytesByExt
) { }
