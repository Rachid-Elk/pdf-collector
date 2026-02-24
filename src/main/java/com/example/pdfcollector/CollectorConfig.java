package com.example.pdfcollector;

import java.nio.file.Path;
import java.util.Set;

public record CollectorConfig(
        Path sourceDir,
        Path destDir,
        boolean sortByDate,
        boolean validateFast,
        boolean moveInsteadOfCopy,
        boolean verboseLog,
        int threads,
        int maxIo,
        int progressEvery,
        Set<String> extensions,
        boolean skipExisting // NEW
) { }
