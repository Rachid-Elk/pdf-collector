package com.example.pdfcollector;


import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {

        // --- Splash stage ---
        FXMLLoader splashLoader = new FXMLLoader(MainApp.class.getResource("splash.fxml"));
        VBox splashRoot = splashLoader.load();
        Scene splashScene = new Scene(splashRoot);

        Stage splashStage = new Stage(StageStyle.UNDECORATED);
        splashStage.setScene(splashScene);
        splashStage.centerOnScreen();
        splashStage.show();

        ProgressBar pb = (ProgressBar) splashLoader.getNamespace().get("pb");
        Label msg = (Label) splashLoader.getNamespace().get("msg");

        // --- Load main UI in background ---
        Task<Scene> loadTask = new Task<>() {
            @Override
            protected Scene call() throws Exception {
                updateMessage("Chargement de l'interface...");
                updateProgress(0.3, 1.0);

                FXMLLoader mainLoader = new FXMLLoader(MainApp.class.getResource("pdf_collector.fxml"));
                Parent root = mainLoader.load();
                updateProgress(1.0, 1.0);
                updateMessage("Prêt");

                return new Scene(root, 980, 620);
            }
        };

        pb.progressProperty().bind(loadTask.progressProperty());
        msg.textProperty().bind(loadTask.messageProperty());

        loadTask.setOnSucceeded(e -> {
            try {
                primaryStage.setTitle("PDF Collector - All_Fichier");
                primaryStage.setScene(loadTask.getValue());
                splashStage.close();
                primaryStage.show();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        loadTask.setOnFailed(e -> {
            loadTask.getException().printStackTrace();
            msg.textProperty().unbind();
            msg.setText("Erreur au démarrage");
        });

        Thread t = new Thread(loadTask, "ui-loader");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
