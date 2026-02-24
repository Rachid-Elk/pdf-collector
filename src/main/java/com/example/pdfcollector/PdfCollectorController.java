package com.example.pdfcollector;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.SelectionMode;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Set;
import java.util.stream.Collectors;

public class PdfCollectorController {

    @FXML private TextField sourceField;
    @FXML private TextField destField;

    @FXML private CheckBox sortByDateCheck;
    @FXML private CheckBox validateFastCheck;
    @FXML private CheckBox moveInsteadCopyCheck;
    @FXML private CheckBox verboseLogCheck;

    @FXML private CheckBox skipExistingCheck; // NEW

    @FXML private Spinner<Integer> threadsSpinner;
    @FXML private Spinner<Integer> maxIoSpinner;
    @FXML private Spinner<Integer> progressEverySpinner;

    @FXML private ListView<String> extListView;
    @FXML private Label validatedExtLabel;

    @FXML private Button scanBtn;
    @FXML private Button startBtn;
    @FXML private Button stopBtn;

    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;

    // Table stats scan
    @FXML private TableView<ExtStatRow> statsTable;
    @FXML private TableColumn<ExtStatRow, String> colExt;
    @FXML private TableColumn<ExtStatRow, Integer> colCount;
    @FXML private TableColumn<ExtStatRow, Double> colSizeMb;
    @FXML private TableColumn<ExtStatRow, Double> colPercent;

    private Task<CollectorStats> currentTask;
    private Set<String> validatedExtensions = Set.of("pdf"); // default

    @FXML
    public void initialize() {
        int cores = Runtime.getRuntime().availableProcessors();

        int defaultThreads = Math.min(32, Math.max(2, cores * 2));
        int defaultMaxIo = 12;
        int defaultProgressEvery = 200;

        threadsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, defaultThreads));
        maxIoSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, defaultMaxIo));
        progressEverySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 5000, defaultProgressEvery));

        // extensions list
        extListView.setItems(FXCollections.observableArrayList(
                "pdf",
                "xlsx","xls",
                "docx","doc",
                "pptx","ppt",
                "csv",
                "png","jpg","jpeg",
                "txt",
                "bat",
                "dump",
                "zip"
        ));
        extListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        extListView.getSelectionModel().clearSelection();
        extListView.getSelectionModel().select("pdf");

        validatedExtensions = Set.of("pdf");
        validatedExtLabel.setText("Sélection validée : pdf");

        progressBar.setProgress(0);
        statusLabel.setText("Prêt");

        // table columns
        colExt.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().ext()));
        colCount.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().count()));
        colSizeMb.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().sizeMb()));
        colPercent.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().percent()));

        colSizeMb.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? "" : String.format("%.2f", v));
            }
        });
        colPercent.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? "" : String.format("%.1f", v));
            }
        });

        appendLog("CPU logical cores detected: " + cores);
        appendLog("Defaults: threads=" + defaultThreads + " maxIO=" + defaultMaxIo + " progressEvery=" + defaultProgressEvery);
    }

    // -------- Extensions UI --------
    @FXML public void onSelectAllExt() { extListView.getSelectionModel().selectAll(); }
    @FXML public void onSelectPdfOnly() {
        extListView.getSelectionModel().clearSelection();
        extListView.getSelectionModel().select("pdf");
    }
    @FXML public void onClearExt() { extListView.getSelectionModel().clearSelection(); }

    @FXML
    public void onValidateExtSelection() {
        Set<String> exts = extListView.getSelectionModel().getSelectedItems()
                .stream()
                .map(s -> s == null ? "" : s.toLowerCase().replace(".", "").trim())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        if (exts.isEmpty()) {
            showError("Aucune extension", "Sélectionne au moins un type puis clique sur OK.");
            return;
        }

        validatedExtensions = exts;
        validatedExtLabel.setText("Sélection validée : " + String.join(", ", validatedExtensions));
        appendLog("Extensions VALIDÉES: " + String.join(", ", validatedExtensions));
    }

    // -------- Browse --------
    @FXML
    public void onBrowseSource() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choisir le dossier source");
        File selected = chooser.showDialog(startBtn.getScene().getWindow());
        if (selected != null) {
            sourceField.setText(selected.getAbsolutePath());
            Path src = Path.of(selected.getAbsolutePath());
            destField.setText(src.resolve("All_Fichier").toString());
        }
    }

    @FXML
    public void onBrowseDest() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choisir le dossier destination");
        File selected = chooser.showDialog(startBtn.getScene().getWindow());
        if (selected != null) destField.setText(selected.getAbsolutePath());
    }

    // -------- SCAN (dry-run + benchmark + estimation) --------
    @FXML
    public void onScan() {
        if (currentTask != null && currentTask.isRunning()) {
            appendLog("Une opération est déjà en cours...");
            return;
        }

        String srcTxt = val(sourceField);
        if (srcTxt.isEmpty()) {
            showError("Source vide", "Choisis un dossier source.");
            return;
        }

        String destTxt = val(destField);
        if (destTxt.isEmpty()) {
            destTxt = Path.of(srcTxt).resolve("All_Fichier").toString();
            destField.setText(destTxt);
        }

        if (validatedExtensions == null || validatedExtensions.isEmpty()) {
            showError("Extensions non validées", "Clique sur OK pour valider tes choix.");
            return;
        }

        CollectorConfig cfg = new CollectorConfig(
                Path.of(srcTxt),
                Path.of(destTxt),
                sortByDateCheck.isSelected(),
                validateFastCheck.isSelected(),
                moveInsteadCopyCheck.isSelected(),
                verboseLogCheck.isSelected(),
                threadsSpinner.getValue(),
                maxIoSpinner.getValue(),
                progressEverySpinner.getValue(),
                validatedExtensions,
                skipExistingCheck.isSelected()
        );

        scanBtn.setDisable(true);
        startBtn.setDisable(true);
        stopBtn.setDisable(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        statusLabel.setText("Scan...");

        CollectorEngine engine = new CollectorEngine();

        Task<Void> scanTask = new Task<>() {
            @Override
            protected Void call() throws Exception {

                ScanResult r = engine.scan(
                        cfg,
                        msg -> Platform.runLater(() -> appendLog(msg)),
                        this::isCancelled
                );
                if (isCancelled()) return null;

                BenchmarkResult b = engine.benchmarkSSD(
                        cfg.destDir(),
                        256,
                        msg -> Platform.runLater(() -> appendLog(msg))
                );
                if (isCancelled()) return null;

                double totalMB = r.totalBytes() / (1024.0 * 1024.0);

                // marge réaliste
                double overheadFactor = 1.35;
                double effective = Math.max(1.0, b.effectiveMBps() / overheadFactor);

                double seconds = totalMB / effective;
                long s = (long) Math.ceil(seconds);
                long hh = s / 3600;
                long mm = (s % 3600) / 60;
                long ss = s % 60;

                // Table rows
                var rows = r.countByExt().keySet().stream().sorted().map(ext -> {
                    int c = r.countByExt().getOrDefault(ext, 0);
                    long bytes = r.bytesByExt().getOrDefault(ext, 0L);
                    double mb = bytes / (1024.0 * 1024.0);
                    double pct = totalMB <= 0 ? 0.0 : (mb * 100.0 / totalMB);
                    return new ExtStatRow(ext, c, mb, pct);
                }).toList();

                Platform.runLater(() -> {
                    statsTable.setItems(FXCollections.observableArrayList(rows));

                    String types = String.join(", ", validatedExtensions);

                    appendLog("=== SCAN RESULT ===");
                    appendLog("Types sélectionnés : " + types);
                    appendLog("Total fichiers     : " + r.totalFiles());
                    appendLog(String.format("Taille totale (MB) : %.2f", totalMB));
                    appendLog("Stats par extension :");
                    rows.forEach(row -> appendLog(
                            "  - " + row.ext() + " : " + row.count() + " fichiers, " +
                                    String.format("%.2f", row.sizeMb()) + " MB (" +
                                    String.format("%.1f", row.percent()) + "%)"
                    ));

                    appendLog(String.format("Débit SSD (write/read) : %.0f / %.0f MB/s", b.writeMBps(), b.readMBps()));
                    appendLog(String.format("Débit estimé (copy)    : %.0f MB/s (marge %.2fx)", effective, overheadFactor));
                    appendLog(String.format("Estimation du temps    : %02d:%02d:%02d (hh:mm:ss)", hh, mm, ss));
                });

                return null;
            }
        };

        scanTask.setOnSucceeded(e -> {
            progressBar.setProgress(0);
            scanBtn.setDisable(false);
            startBtn.setDisable(false);
            stopBtn.setDisable(true);
            statusLabel.setText("Scan terminé ✔");
        });

        scanTask.setOnFailed(e -> {
            progressBar.setProgress(0);
            scanBtn.setDisable(false);
            startBtn.setDisable(false);
            stopBtn.setDisable(true);

            appendLog("=== SCAN ERROR ===");
            Throwable ex = scanTask.getException();
            appendLog(ex == null ? "Erreur inconnue" : ex.toString());
            statusLabel.setText("Erreur scan ❌");
        });

        Thread t = new Thread(scanTask, "scan-task");
        t.setDaemon(true);
        t.start();
    }

    // -------- START/STOP (copy/move) --------
    @FXML
    public void onStart() {
        if (currentTask != null && currentTask.isRunning()) {
            appendLog("Déjà en cours...");
            return;
        }

        String srcTxt = val(sourceField);
        if (srcTxt.isEmpty()) { showError("Source vide", "Choisis un dossier source."); return; }

        String destTxt = val(destField);
        if (destTxt.isEmpty()) {
            destTxt = Path.of(srcTxt).resolve("All_Fichier").toString();
            destField.setText(destTxt);
        }

        if (validatedExtensions == null || validatedExtensions.isEmpty()) {
            showError("Extensions non validées", "Clique sur OK pour valider tes choix.");
            return;
        }

        CollectorConfig cfg = new CollectorConfig(
                Path.of(srcTxt),
                Path.of(destTxt),
                sortByDateCheck.isSelected(),
                validateFastCheck.isSelected(),
                moveInsteadCopyCheck.isSelected(),
                verboseLogCheck.isSelected(),
                threadsSpinner.getValue(),
                maxIoSpinner.getValue(),
                progressEverySpinner.getValue(),
                validatedExtensions,
                skipExistingCheck.isSelected()
        );

        scanBtn.setDisable(true);
        startBtn.setDisable(true);
        stopBtn.setDisable(false);

        progressBar.setProgress(0);
        statusLabel.setText("Démarrage...");

        CollectorEngine engine = new CollectorEngine();

        currentTask = new Task<>() {
            @Override
            protected CollectorStats call() throws Exception {
                return engine.run(
                        cfg,
                        p -> { updateProgress(p, 1.0); updateMessage("Progress: " + (int)Math.round(p * 100) + "%"); },
                        msg -> Platform.runLater(() -> appendLog(msg)),
                        this::isCancelled
                );
            }
        };

        progressBar.progressProperty().bind(currentTask.progressProperty());
        statusLabel.textProperty().bind(currentTask.messageProperty());

        currentTask.setOnSucceeded(e -> onEndOk(currentTask.getValue()));
        currentTask.setOnFailed(e -> onEndFail(currentTask.getException()));
        currentTask.setOnCancelled(e -> onEndCancelled());

        Thread t = new Thread(currentTask, "collector-task");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onStop() {
        if (currentTask != null) {
            appendLog("Stop demandé...");
            currentTask.cancel();
        }
    }

    private void onEndOk(CollectorStats st) {
        unbindStatus();
        scanBtn.setDisable(false);
        startBtn.setDisable(false);
        stopBtn.setDisable(true);

        DecimalFormat df = new DecimalFormat("#,##0.00");
        double mb = st.totalBytes() / (1024.0 * 1024.0);

        String types = (validatedExtensions == null || validatedExtensions.isEmpty())
                ? "(aucun)"
                : String.join(", ", validatedExtensions);

        appendLog("=== DONE ===");
        appendLog("Types de fichiers     : " + types);
        appendLog("Total trouvés         : " + st.totalFound());
        appendLog("Copiés/Déplacés       : " + st.copiedOrMoved());
        appendLog("Ignorés (déjà exist.) : " + st.skipped());
        appendLog("Corrompus (" + types + ") : " + st.corrupted());
        appendLog("Échecs (" + types + ")    : " + st.failed());
        appendLog("Taille totale         : " + df.format(mb) + " MB");

        statusLabel.setText("Terminé ✔");
    }

    private void onEndFail(Throwable ex) {
        unbindStatus();
        scanBtn.setDisable(false);
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        appendLog("=== ERROR ===");
        appendLog(ex == null ? "Erreur inconnue" : ex.toString());
        statusLabel.setText("Erreur ❌");
    }

    private void onEndCancelled() {
        unbindStatus();
        scanBtn.setDisable(false);
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        appendLog("=== CANCELLED ===");
        statusLabel.setText("Arrêté.");
    }

    private void unbindStatus() {
        try {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
        } catch (Exception ignored) {}
    }

    private void appendLog(String msg) { logArea.appendText(msg + "\n"); }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }

    private static String val(TextField tf) {
        return tf.getText() == null ? "" : tf.getText().trim();
    }
}