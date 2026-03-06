package com.example.pdfcollector;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Label errorLabel;

    private int attempts = 0;

    @FXML
    private void onLogin() {

        String user = usernameField.getText();
        String pass = passwordField.getText();

        boolean ok = AuthService.authenticate(user, pass);

        if (ok) {

            openMainApp();

        } else {

            attempts++;

            if (attempts >= 3) {

                errorLabel.setText("Application bloquée après 3 essais");

                usernameField.setDisable(true);
                passwordField.setDisable(true);

            } else {

                errorLabel.setText("Utilisateur ou mot de passe incorrect");

            }

        }
    }

    private void openMainApp() {

        try {

            FXMLLoader loader =
                    new FXMLLoader(getClass()
                            .getResource("pdf_collector.fxml"));

            Stage stage = new Stage();

            stage.setScene(new Scene(loader.load()));
            stage.setTitle("PDF Collector");

            stage.show();

            Stage loginStage =
                    (Stage) usernameField.getScene().getWindow();

            loginStage.close();

        } catch (Exception e) {

            e.printStackTrace();

        }
    }
}