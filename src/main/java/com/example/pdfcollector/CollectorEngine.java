package com.example.pdfcollector;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class CollectorEngine {

    public CollectorStats run(
            CollectorConfig cfg,
            Consumer<Double> progress,     // 0..1
            Consumer<String> logger,
            BooleanSupplier isCancelled
    ) throws Exception {

        Objects.requireNonNull(cfg);
        Objects.requireNonNull(progress);
        Objects.requireNonNull(logger);
        Objects.requireNonNull(isCancelled);

        Path source = cfg.sourceDir();
        Path dest = cfg.destDir();

        if (!Files.exists(source) || !Files.isDirectory(source)) {
            throw new IllegalArgumentException("Source invalide: " + source.toAbsolutePath());
        }
        Files.createDirectories(dest);

        logger.accept("Scan: " + source.toAbsolutePath());
        logger.accept("Dest: " + dest.toAbsolutePath());

        // 1) Scan
        List<Path> pdfs = new ArrayList<>();
        Set<String> allowed = cfg.extensions();

        try (var stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(dest))
                    .filter(p -> allowed.contains(extOf(p)))
                    .forEach(pdfs::add);
        }
        // 2) Sort (optional)
        if (cfg.sortByDate()) {
            pdfs.sort(Comparator.comparing(p -> {
                try { return Files.getLastModifiedTime(p); }
                catch (Exception e) { return FileTime.from(Instant.EPOCH); }
            }));
        }

        int total = pdfs.size();
        String types = String.join("- ", cfg.extensions());
        logger.accept("Les fichiers trouvés (" + types + ") : " + total);
        if (total == 0) {
            progress.accept(1.0);
            return new CollectorStats(0, 0, 0, 0, 0L);
        }

        // Stats
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger copied = new AtomicInteger(0);
        AtomicInteger corrupted = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicLong totalBytes = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(cfg.threads());
        Semaphore ioLimiter = new Semaphore(cfg.maxIo());

        List<Future<?>> futures = new ArrayList<>(total);

        long startNs = System.nanoTime();

        for (Path src : pdfs) {
            if (isCancelled.getAsBoolean()) break;

            futures.add(pool.submit(() -> {
                try {
                    if (isCancelled.getAsBoolean()) return;

                    String ext = extOf(src);
                    if (cfg.validateFast() && "pdf".equals(ext) && !looksLikePdfFast(src)) {
                        corrupted.incrementAndGet();
                        if (cfg.verboseLog()) logger.accept("[CORRUPTED] " + src.toAbsolutePath());
                        return;
                    }


                    Path destFile = dest.resolve(src.getFileName());
                    destFile = resolveDuplicate(destFile);

                    ioLimiter.acquire();
                    try {
                        if (isCancelled.getAsBoolean()) return;

                        if (cfg.moveInsteadOfCopy()) {
                            // MOVE
                            Files.move(src, destFile, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            // COPY
                            Files.copy(src, destFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        ioLimiter.release();
                    }

                    long size = safeSize(src);
                    copied.incrementAndGet();
                    totalBytes.addAndGet(size);

                    if (cfg.verboseLog()) {
                        logger.accept("[OK] " + src.toAbsolutePath() + " -> " + destFile.toAbsolutePath());
                    }

                } catch (Exception e) {
                    failed.incrementAndGet();
                    logger.accept("[FAIL] " + src.toAbsolutePath() + " | " + e.getMessage());
                } finally {
                    int done = processed.incrementAndGet();

                    if (done % cfg.progressEvery() == 0 || done == total) {
                        progress.accept(done / (double) total);
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                        // log light
                        if (!cfg.verboseLog()) {
                            logger.accept("Progress: " + (int)Math.round(done * 100.0 / total) + "% (" + done + "/" + total + ") elapsed=" + elapsedMs + "ms");
                        }
                    }
                }
            }));
        }

        // wait
        for (Future<?> f : futures) {
            if (isCancelled.getAsBoolean()) break;
            try { f.get(); }
            catch (ExecutionException ignored) { /* already logged */ }
        }

        pool.shutdownNow();

        progress.accept(1.0);

        return new CollectorStats(
                total,
                copied.get(),
                corrupted.get(),
                failed.get(),
                totalBytes.get()
        );
    }
    private static String extOf(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    // Fast corruption check: header %PDF- and EOF marker in last ~2KB
    private static boolean looksLikePdfFast(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            if (len < 12) return false;

            byte[] head = new byte[5];
            raf.seek(0);
            raf.readFully(head);
            if (!"%PDF-".equals(new String(head, StandardCharsets.US_ASCII))) return false;

            int window = (int) Math.min(2048, len);
            byte[] tail = new byte[window];
            raf.seek(len - window);
            raf.readFully(tail);
            String tailStr = new String(tail, StandardCharsets.US_ASCII);
            return tailStr.contains("%%EOF");
        } catch (Exception e) {
            return false;
        }
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); }
        catch (Exception e) { return 0L; }
    }

    private static Path resolveDuplicate(Path file) throws Exception {
        // always unique naming to avoid collisions
        if (!Files.exists(file)) return file;

        String name = file.getFileName().toString();
        String base = name;
        String ext = "";

        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }

        int i = 1;
        Path parent = file.getParent();
        Path candidate;
        do {
            candidate = parent.resolve(base + "_" + i + ext);
            i++;
        } while (Files.exists(candidate));

        return candidate;
    }
}
