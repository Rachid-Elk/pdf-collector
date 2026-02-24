package com.example.pdfcollector;

import java.io.OutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class CollectorEngine {

    public CollectorStats run(CollectorConfig cfg,
                              Consumer<Double> progress,
                              Consumer<String> logger,
                              BooleanSupplier isCancelled) throws Exception {

        Objects.requireNonNull(cfg);
        Objects.requireNonNull(progress);
        Objects.requireNonNull(logger);
        Objects.requireNonNull(isCancelled);

        Path source = cfg.sourceDir();
        Path dest = cfg.destDir();
        Files.createDirectories(dest);

        // 1) Scan fichiers à traiter (selon extensions)
        List<Path> files = new ArrayList<>();
        Set<String> allowed = cfg.extensions();

        try (var stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(dest))
                    .filter(p -> allowed.contains(extOf(p)))
                    .forEach(files::add);
        }

        // Tri par date si demandé
        if (cfg.sortByDate()) {
            files.sort(Comparator.comparingLong(this::safeLastModified));
        }

        String types = String.join(", ", allowed);
        logger.accept("Les fichiers trouvés (types : " + types + ") : " + files.size());

        // 2) Copie / Move en multi-thread avec limite I/O
        int total = files.size();
        if (total == 0) {
            progress.accept(1.0);
            return new CollectorStats(0, 0, 0, 0, 0, 0L);
        }

        int threads = Math.max(1, cfg.threads());
        int maxIo = Math.max(1, cfg.maxIo());
        int progressEvery = Math.max(1, cfg.progressEvery());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Semaphore ioLimiter = new Semaphore(maxIo);

        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger copied = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger corrupted = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        long[] totalBytes = new long[]{0L};
        Object bytesLock = new Object();

        try {
            List<Future<?>> futures = new ArrayList<>(total);

            for (Path src : files) {
                if (isCancelled.getAsBoolean()) break;

                futures.add(pool.submit(() -> {
                    if (isCancelled.getAsBoolean()) return;

                    try {
                        ioLimiter.acquire();

                        // Validation rapide PDF uniquement
                        String ext = extOf(src);
                        if (cfg.validateFast() && "pdf".equals(ext) && !looksLikePdfFast(src)) {
                            corrupted.incrementAndGet();
                            if (cfg.verboseLog()) logger.accept("[CORRUPTED] " + src.toAbsolutePath());
                            return;
                        }

                        // Destination aplatie (tout dans All_Fichier)
                        Path destFile = dest.resolve(src.getFileName().toString());

                        // Copy intelligente: skip si existe déjà même taille, sinon collision -> rename
                        if (cfg.skipExisting() && Files.exists(destFile)) {
                            long srcSize = safeSize(src);
                            long dstSize = safeSize(destFile);

                            if (srcSize > 0 && srcSize == dstSize) {
                                skipped.incrementAndGet();
                                if (cfg.verboseLog()) logger.accept("[SKIP] " + src.toAbsolutePath());
                                return;
                            } else {
                                destFile = uniqueName(destFile);
                            }
                        } else if (Files.exists(destFile)) {
                            // si skipExisting désactivé => éviter écrasement quand même
                            destFile = uniqueName(destFile);
                        }

                        // Copie / move
                        if (cfg.moveInsteadOfCopy()) {
                            Files.move(src, destFile, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.copy(src, destFile, StandardCopyOption.REPLACE_EXISTING);
                        }

                        copied.incrementAndGet();

                        long sz = safeSize(destFile);
                        synchronized (bytesLock) {
                            totalBytes[0] += sz;
                        }

                        if (cfg.verboseLog()) {
                            logger.accept("[OK] " + src.toAbsolutePath() + " -> " + destFile.toAbsolutePath());
                        }

                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        logger.accept("[FAIL] " + src.toAbsolutePath() + " : " + ex.getMessage());
                    } finally {
                        ioLimiter.release();

                        int d = done.incrementAndGet();
                        if (d % progressEvery == 0 || d == total) {
                            progress.accept(d / (double) total);
                            if (!cfg.verboseLog()) {
                                logger.accept("Progress: " + (int) Math.round((d * 100.0) / total) + "% (" + d + "/" + total + ")");
                            }
                        }
                    }
                }));
            }

            for (Future<?> f : futures) {
                if (isCancelled.getAsBoolean()) break;
                try { f.get(); } catch (Exception ignored) {}
            }

        } finally {
            pool.shutdownNow();
        }

        progress.accept(1.0);

        return new CollectorStats(
                total,
                copied.get(),
                skipped.get(),
                corrupted.get(),
                failed.get(),
                totalBytes[0]
        );
    }

    // ------------------ SCAN (dry run) ------------------
    public ScanResult scan(CollectorConfig cfg, Consumer<String> logger, BooleanSupplier isCancelled) throws Exception {
        var countByExt = new HashMap<String, Integer>();
        var bytesByExt = new HashMap<String, Long>();
        long totalBytes = 0L;
        int total = 0;

        var allowed = cfg.extensions();
        var source = cfg.sourceDir();
        var dest = cfg.destDir();

        try (var stream = Files.walk(source)) {
            for (var it = stream.iterator(); it.hasNext();) {
                if (isCancelled.getAsBoolean()) break;

                var p = it.next();
                if (!Files.isRegularFile(p)) continue;
                if (p.startsWith(dest)) continue;

                String ext = extOf(p);
                if (!allowed.contains(ext)) continue;

                total++;
                countByExt.merge(ext, 1, Integer::sum);

                long sz = safeSize(p);
                totalBytes += sz;
                bytesByExt.merge(ext, sz, Long::sum);
            }
        }

        String types = String.join(", ", allowed);
        logger.accept("Les fichiers trouvés (types : " + types + ") : " + total);

        return new ScanResult(total, totalBytes, countByExt, bytesByExt);
    }

    // ------------------ SSD benchmark ------------------
    public BenchmarkResult benchmarkSSD(Path destDir, int sizeMB, Consumer<String> logger) throws Exception {
        Files.createDirectories(destDir);

        Path temp = destDir.resolve(".benchmark_tmp.bin");

        byte[] buffer = new byte[1024 * 1024]; // 1MB
        for (int i = 0; i < buffer.length; i += 4096) buffer[i] = (byte) (i * 31);

        long startW = System.nanoTime();
        try (OutputStream os = Files.newOutputStream(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int i = 0; i < sizeMB; i++) os.write(buffer);
            os.flush();
        }
        long endW = System.nanoTime();
        double writeSec = (endW - startW) / 1_000_000_000.0;
        double writeMBps = sizeMB / Math.max(writeSec, 0.0001);

        long startR = System.nanoTime();
        try (var is = Files.newInputStream(temp, StandardOpenOption.READ)) {
            int read;
            while ((read = is.read(buffer)) != -1) { /* consume */ }
        }
        long endR = System.nanoTime();
        double readSec = (endR - startR) / 1_000_000_000.0;
        double readMBps = sizeMB / Math.max(readSec, 0.0001);

        try { Files.deleteIfExists(temp); } catch (Exception ignored) {}

        double effective = 1.0 / (1.0 / readMBps + 1.0 / writeMBps); // harmonic mean

        if (logger != null) {
            logger.accept(String.format(
                    "Benchmark SSD (%dMB): write=%.0f MB/s, read=%.0f MB/s, effective(copy)=%.0f MB/s",
                    sizeMB, writeMBps, readMBps, effective
            ));
        }
        return new BenchmarkResult(writeMBps, readMBps, effective);
    }

    // ------------------ Helpers ------------------
    private static String extOf(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0L; }
    }

    private long safeLastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (Exception e) { return Instant.EPOCH.toEpochMilli(); }
    }

    private static Path uniqueName(Path dest) {
        String name = dest.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        String ext = (dot >= 0) ? name.substring(dot) : "";

        Path dir = dest.getParent();
        for (int i = 1; i < 10_000; i++) {
            Path candidate = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return dir.resolve(base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    private static boolean looksLikePdfFast(Path p) {
        // fast check: starts with %PDF
        try (var in = Files.newInputStream(p)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            if (n < 4) return false;
            return head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }
}