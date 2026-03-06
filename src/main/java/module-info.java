module com.example.pdfcollector {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.fasterxml.jackson.databind;

    opens com.example.pdfcollector to javafx.fxml, com.fasterxml.jackson.databind;
    exports com.example.pdfcollector;
}