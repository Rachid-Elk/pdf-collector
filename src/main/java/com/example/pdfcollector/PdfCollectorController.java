package com.example.pdfcollector;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.text.DecimalFormat;

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

    @FXML private Button startBtn;
    @FXML private Button stopBtn;

    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;

    private Task<CollectorStats> currentTask;

    @FXML
    public void initialize() {
        int cores = Runtime.getRuntime().availableProcessors();

        // SSD-friendly defaults (tu peux changer dans l'UI)
        int defaultThreads = Math.min(32, cores * 4);
        int defaultMaxIo = Math.min(16, Math.max(8, cores / 2)); // safe default; user can set 12..20
        int defaultProgressEvery = 200;

        threadsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, defaultThreads));
        maxIoSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, defaultMaxIo));
        progressEverySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 5000, defaultProgressEvery));

        progressBar.setProgress(0);
        statusLabel.setText("Prêt");
        appendLog("CPU logical cores detected: " + cores);
        appendLog("Defaults: threads=" + defaultThreads + " maxIO=" + defaultMaxIo + " progressEvery=" + defaultProgressEvery);
    }

    @FXML
    public void onBrowseSource() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choisir le dossier source");
        File selected = chooser.showDialog(startBtn.getScene().getWindow());
        if (selected != null) {
            sourceField.setText(selected.getAbsolutePath());

            // auto default dest: source/All_Fichier
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

    @FXML
    public void onStart() {
        if (currentTask != null && currentTask.isRunning()) {
            appendLog("Déjà en cours...");
            return;
        }

        String srcTxt = sourceField.getText().trim();
        if (srcTxt.isEmpty()) {
            showError("Source vide", "Choisis un dossier source.");
            return;
        }

        String destTxt = destField.getText().trim();
        if (destTxt.isEmpty()) {
            // fallback
            destTxt = Path.of(srcTxt).resolve("All_Fichier").toString();
            destField.setText(destTxt);
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
                progressEverySpinner.getValue()
        );

        startBtn.setDisable(true);
        stopBtn.setDisable(false);
        progressBar.setProgress(0);
        statusLabel.setText("Démarrage...");

        logArea.clear();
        appendLog("=== START ===");
        appendLog("source=" + cfg.sourceDir().toAbsolutePath());
        appendLog("dest=" + cfg.destDir().toAbsolutePath());
        appendLog("threads=" + cfg.threads() + " maxIO=" + cfg.maxIo());
        appendLog("sortByDate=" + cfg.sortByDate() + " validateFast=" + cfg.validateFast());
        appendLog("moveInsteadOfCopy=" + cfg.moveInsteadOfCopy() + " verboseLog=" + cfg.verboseLog());

        CollectorEngine engine = new CollectorEngine();

        currentTask = new Task<>() {
            @Override
            protected CollectorStats call() throws Exception {
                return engine.run(
                        cfg,
                        p -> {
                            // from worker threads => updateProgress is thread-safe
                            updateProgress(p, 1.0);
                            updateMessage("Progress: " + (int)Math.round(p * 100) + "%");
                        },
                        msg -> Platform.runLater(() -> appendLog(msg)),
                        this::isCancelled
                );
            }
        };

        progressBar.progressProperty().bind(currentTask.progressProperty());
        statusLabel.textProperty().bind(currentTask.messageProperty());

        currentTask.setOnSucceeded(e -> {
            CollectorStats st = currentTask.getValue();
            onEndOk(st);
        });

        currentTask.setOnFailed(e -> {
            Throwable ex = currentTask.getException();
            onEndFail(ex);
        });

        currentTask.setOnCancelled(e -> {
            onEndCancelled();
        });

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
        startBtn.setDisable(false);
        stopBtn.setDisable(true);

        DecimalFormat df = new DecimalFormat("#,##0.00");
        double mb = st.totalBytes() / (1024.0 * 1024.0);

        appendLog("=== DONE ===");
        appendLog("Total PDF trouvés : " + st.totalFound());
        appendLog("Copiés/Déplacés   : " + st.copiedOrMoved());
        appendLog("Corrompus         : " + st.corrupted());
        appendLog("Échecs            : " + st.failed());
        appendLog("Taille totale     : " + df.format(mb) + " MB");

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
        } catch (Exception ignored) {}
    }

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
