package com.zalo.client.ui;

import com.zalo.client.App;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Màn hình Đăng nhập / Đăng ký. */
public final class LoginView extends VBox {
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField user = new TextField();
    private final PasswordField pass = new PasswordField();
    private final TextField phone = new TextField();
    private final TextField display = new TextField();
    private final Label status = new Label();
    private final Button loginBtn = new Button("Đăng nhập");
    private final Button regBtn = new Button("Đăng ký");
    private boolean registerMode;

    public LoginView(App app, String defHost, String defPort, String message) {
        setSpacing(12);
        setPadding(new Insets(24));
        setAlignment(Pos.CENTER);
        setMaxWidth(380);

        Label title = new Label("Zalo (đồ án mạng)");
        title.setStyle("-fx-font-size:24px;-fx-font-weight:bold;-fx-text-fill:#0068ff;");

        host.setText(defHost);
        port.setText(defPort);
        user.setPromptText("Tên đăng nhập");
        pass.setPromptText("Mật khẩu");
        phone.setPromptText("Số điện thoại");
        display.setPromptText("Tên hiển thị");

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        g.addRow(0, new Label("Server"), host, port);
        port.setPrefWidth(70);
        host.setPrefWidth(220);
        g.addRow(1, new Label("Tài khoản"), user);
        g.addRow(2, new Label("Mật khẩu"), pass);
        g.addRow(3, new Label("SĐT"), phone);
        g.addRow(4, new Label("Tên hiển thị"), display);
        for (int r : new int[]{3, 4}) {
            g.getChildren().forEach(n -> { if (GridPane.getRowIndex(n) != null && GridPane.getRowIndex(n) == r) { n.setVisible(false); n.setManaged(false); } });
        }
        status.setWrapText(true);
        status.setStyle("-fx-text-fill:#d33;");
        if (message != null) status.setText(message);

        loginBtn.setDefaultButton(true);
        loginBtn.setOnAction(e -> {
            if (registerMode) {
                app.doRegister(host.getText().trim(), port.getText().trim(), user.getText().trim(), phone.getText().trim(),
                        pass.getText(), display.getText().trim());
            } else {
                setBusy(true, "Đang kết nối...");
                app.doLogin(host.getText().trim(), port.getText().trim(), user.getText().trim(), pass.getText());
            }
        });
        regBtn.setOnAction(e -> {
            registerMode = !registerMode;
            regBtn.setText(registerMode ? "← Quay lại đăng nhập" : "Đăng ký");
            loginBtn.setText(registerMode ? "Tạo tài khoản" : "Đăng nhập");
            g.getChildren().forEach(n -> {
                Integer r = GridPane.getRowIndex(n);
                if (r != null && (r == 3 || r == 4)) { n.setVisible(registerMode); n.setManaged(registerMode); }
            });
            status.setText("");
        });
        getChildren().addAll(title, g, loginBtn, regBtn, status);
    }

    public void setBusy(boolean busy, String msg) {
        loginBtn.setDisable(busy);
        regBtn.setDisable(busy);
        status.setStyle("-fx-text-fill:" + (busy ? "#555" : "#d33") + ";");
        status.setText(msg == null ? "" : msg);
    }

    public void showOk(String msg) {
        status.setStyle("-fx-text-fill:#1a8f3c;");
        status.setText(msg);
    }
}
