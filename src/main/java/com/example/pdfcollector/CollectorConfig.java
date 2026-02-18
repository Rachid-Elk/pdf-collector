package com.example.pdfcollector;

import java.nio.file.Path;

public record CollectorConfig(
        Path sourceDir,
        Path destDir,
        boolean sortByDate,
        boolean validateFast,
        boolean moveInsteadOfCopy,
        boolean verboseLog,
        int threads,
        int maxIo,
        int progressEvery
) { }
