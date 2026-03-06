package com.example.pdfcollector;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            URL fxml = MainApp.class.getResource("/com/example/pdfcollector/login.fxml");
            if (fxml == null) {
                throw new IllegalStateException(
                        "login.fxml introuvable. Vérifie: src/main/resources/com/example/pdfcollector/login.fxml"
                );
            }

            Parent root = FXMLLoader.load(fxml);

            primaryStage.setTitle("Login");
            primaryStage.setScene(new Scene(root, 420, 320));
            primaryStage.setResizable(false);
            primaryStage.setOnCloseRequest(e -> Platform.exit());
            primaryStage.show();

        } catch (Exception e) {
            e.printStackTrace();
            Platform.exit();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}