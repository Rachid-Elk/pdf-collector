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

    @FXML private Spinner<Integer> threadsSpinner;
    @FXML private Spinner<Integer> maxIoSpinner;
    @FXML private Spinner<Integer> progressEverySpinner;

    // Extensions list + validation UI
    @FXML private ListView<String> extListView;
    @FXML private Label validatedExtLabel;

    @FXML private Button startBtn;
    @FXML private Button stopBtn;

    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;

    private Task<CollectorStats> currentTask;

    // NEW: only these extensions are used for the run (validated via OK button)
    private Set<String> validatedExtensions = Set.of("pdf"); // default

    @FXML
    public void initialize() {
        int cores = Runtime.getRuntime().availableProcessors();

        // SSD-friendly defaults
        int defaultThreads = Math.min(32, Math.max(2, cores * 2));
        int defaultMaxIo = 12; // good default for mixed sizes; user can tune
        int defaultProgressEvery = 200;

        threadsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, defaultThreads));
        maxIoSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, defaultMaxIo));
        progressEverySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 5000, defaultProgressEvery));

        // Extensions list (multi-select)
        extListView.setItems(FXCollections.observableArrayList(
                "pdf",
                "xlsx", "xls",
                "docx", "doc",
                "pptx", "ppt",
                "csv",
                "png", "jpg", "jpeg",
                "txt",
                "bat",
                "dump",
                "zip"
        ));
        extListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Default selection in UI: PDF
        extListView.getSelectionModel().clearSelection();
        extListView.getSelectionModel().select("pdf");

        // Default validated selection: PDF
        validatedExtensions = Set.of("pdf");
        if (validatedExtLabel != null) {
            validatedExtLabel.setText("Sélection validée : pdf");
        }

        progressBar.setProgress(0);
        statusLabel.setText("Prêt");
        appendLog("CPU logical cores detected: " + cores);
        appendLog("Defaults: threads=" + defaultThreads + " maxIO=" + defaultMaxIo + " progressEvery=" + defaultProgressEvery);
        appendLog("Validated extensions (default): pdf");

        // OPTIONAL: force OK before Start (uncomment if you want)
        // startBtn.setDisable(true);
    }

    // ======= Extensions buttons (FXML) =======

    @FXML
    public void onSelectAllExt() {
        extListView.getSelectionModel().selectAll();
        appendLog("Extensions: SELECT ALL (non validé)");
    }

    @FXML
    public void onSelectPdfOnly() {
        extListView.getSelectionModel().clearSelection();
        extListView.getSelectionModel().select("pdf");
        appendLog("Extensions: PDF only (non validé)");
    }

    @FXML
    public void onClearExt() {
        extListView.getSelectionModel().clearSelection();
        appendLog("Extensions: cleared (non validé)");
    }

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

        String types = String.join(", ", validatedExtensions);
        if (validatedExtLabel != null) {
            validatedExtLabel.setText("Sélection validée : " + types);
        }
        appendLog("Extensions VALIDÉES: " + types);

        // OPTIONAL: enable Start only after OK
        // startBtn.setDisable(false);
    }

    // ======= Browse buttons =======

    @FXML
    public void onBrowseSource() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choisir le dossier source");
        File selected = chooser.showDialog(startBtn.getScene().getWindow());
        if (selected != null) {
            sourceField.setText(selected.getAbsolutePath());

            // Default dest: source/All_Fichier
            Path src = Path.of(selected.getAbsolutePath());
            destField.setText(src.resolve("All_Fichier").toString());
        }
    }

    @FXML
    public void onBrowseDest() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choisir le dossier destination");
        File selected = chooser.showDialog(startBtn.getScene().getWindow());
        if (selected != null) {
            destField.setText(selected.getAbsolutePath());
        }
    }

    // ======= Start/Stop =======

    @FXML
    public void onStart() {
        if (currentTask != null && currentTask.isRunning()) {
            appendLog("Déjà en cours...");
            return;
        }

        String srcTxt = sourceField.getText() == null ? "" : sourceField.getText().trim();
        if (srcTxt.isEmpty()) {
            showError("Source vide", "Choisis un dossier source.");
            return;
        }

        String destTxt = destField.getText() == null ? "" : destField.getText().trim();
        if (destTxt.isEmpty()) {
            destTxt = Path.of(srcTxt).resolve("All_Fichier").toString();
            destField.setText(destTxt);
        }

        // IMPORTANT: use only validated extensions (OK button)
        Set<String> exts = validatedExtensions;
        if (exts == null || exts.isEmpty()) {
            showError("Extensions non validées", "Clique sur OK pour valider tes choix de types de fichiers.");
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
                exts
        );

        startBtn.setDisable(true);
        stopBtn.setDisable(false);
        progressBar.setProgress(0);
        statusLabel.setText("Démarrage...");

        logArea.clear();
        appendLog("=== START ===");
        appendLog("source=" + cfg.sourceDir().toAbsolutePath());
        appendLog("dest=" + cfg.destDir().toAbsolutePath());
        appendLog("threads=" + cfg.threads() + " maxIO=" + cfg.maxIo() + " progressEvery=" + cfg.progressEvery());
        appendLog("sortByDate=" + cfg.sortByDate() + " validateFast=" + cfg.validateFast());
        appendLog("moveInsteadOfCopy=" + cfg.moveInsteadOfCopy() + " verboseLog=" + cfg.verboseLog());
        appendLog("extensions(validées)=" + cfg.extensions());

        CollectorEngine engine = new CollectorEngine();

        currentTask = new Task<>() {
            @Override
            protected CollectorStats call() throws Exception {
                return engine.run(
                        cfg,
                        p -> {
                            updateProgress(p, 1.0);
                            updateMessage("Progress: " + (int) Math.round(p * 100) + "%");
                        },
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

    // ======= End handlers =======

    private void onEndOk(CollectorStats st) {
        unbindStatus();
        startBtn.setDisable(false);
        stopBtn.setDisable(true);

        DecimalFormat df = new DecimalFormat("#,##0.00");
        double mb = st.totalBytes() / (1024.0 * 1024.0);

        String types = (validatedExtensions == null || validatedExtensions.isEmpty())
                ? "(aucun)"
                : String.join(", ", validatedExtensions);

        appendLog("=== DONE ===");
        appendLog("Types de fichiers   : " + types);
        appendLog("Total trouvés       : " + types + " -> " + st.totalFound());
        appendLog("Copiés/Déplacés     : " + st.copiedOrMoved());
        appendLog("Corrompus (" + types + ") : " + st.corrupted());
        appendLog("Échecs (" + types + ")    : " + st.failed());
        appendLog("Taille totale       : " + df.format(mb) + " MB");

        statusLabel.setText("Terminé ✔");
    }

    private void onEndFail(Throwable ex) {
        unbindStatus();
        startBtn.setDisable(false);
        stopBtn.setDisable(true);

        appendLog("=== ERROR ===");
        appendLog(ex == null ? "Erreur inconnue" : ex.toString());
        statusLabel.setText("Erreur ❌");
    }

    private void onEndCancelled() {
        unbindStatus();
        startBtn.setDisable(false);
        stopBtn.setDisable(true);

        appendLog("=== CANCELLED ===");
        statusLabel.setText("Arrêté.");
    }

    private void unbindStatus() {
        try {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
        } catch (Exception ignored) {
        }
    }

    // ======= UI helpers =======

    private void appendLog(String msg) {
        logArea.appendText(msg + "\n");
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }
}
