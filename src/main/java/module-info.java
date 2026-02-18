module com.example.pdfcollector {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.example.pdfcollector to javafx.fxml;
    exports com.example.pdfcollector;
}
