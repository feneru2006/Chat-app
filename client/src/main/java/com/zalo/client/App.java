package com.zalo.client;

import com.zalo.client.core.ClientCore;
import com.zalo.client.net.NetworkService;
import com.zalo.client.ui.LoginView;
import com.zalo.client.ui.MainView;
import com.zalo.common.Config;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Khung ứng dụng JavaFX + cầu nối ClientCore -> giao diện.
 * Chạy 2-3 Client trên cùng máy: mvn -pl client javafx:run   (mở terminal mới cho mỗi Client).
 */
public class App extends Application implements ClientCore.Ui {
    private Stage stage;
    private Config cfg;
    private ClientCore core;
    private LoginView login;
    private MainView main;

    @Override
    public void start(Stage s) {
        stage = s;
        cfg = Config.load(System.getProperty("client.config", "client.properties"));
        core = new ClientCore(cfg, this);
        stage.setTitle("Zalo - Lập trình ứng dụng mạng");
        stage.focusedProperty().addListener((o, a, focused) -> core.onFocusChanged(focused));
        stage.setOnCloseRequest(e -> {
            core.shutdown();
            Platform.exit();
            System.exit(0);
        });
        showLogin(null);
        stage.show();
    }

    private void showLogin(String message) {
        main = null;
        login = new LoginView(this, cfg.get("server.host", "localhost"), cfg.get("server.port", "9000"), message);
        StackPane root = new StackPane(login);
        root.setAlignment(Pos.CENTER);
        stage.setScene(new Scene(root, 520, 460));
    }

    // ------------------------------------------------------------------ hành động từ LoginView
    public void doLogin(String host, String port, String user, String pass) {
        System.setProperty("server.host", host);                // Config.get() ưu tiên -D
        System.setProperty("server.port", port);
        try {
            core.login(user, pass);
        } catch (Exception e) {
            login.setBusy(false, "Lỗi khởi động: " + e.getMessage());
        }
    }

    public void doRegister(String host, String port, String user, String phone, String pass, String display) {
        System.setProperty("server.host", host);
        System.setProperty("server.port", port);
        login.setBusy(true, "Đang đăng ký...");
        ClientCore.registerAsync(cfg, user, phone, pass, display, r -> {
            if (r.boolVal("ok", false)) {
                login.setBusy(false, "");
                login.showOk("Đăng ký thành công! Hãy đăng nhập.");
            } else {
                String err = r.str("message") != null ? r.str("message") : r.str("error");
                login.setBusy(false, Protocol.ERR_USERNAME_TAKEN.equals(r.str("error")) ? "Tên đăng nhập hoặc SĐT đã tồn tại" : err);
            }
        });
    }

    // ------------------------------------------------------------------ ClientCore.Ui
    @Override
    public void onAuth(boolean ok, String error) {
        if (ok) {
            main = new MainView(core);
            stage.setScene(new Scene(main, 1000, 680));
            stage.setTitle("Zalo - " + core.myName);
        } else {
            core.shutdown();
            String msg = Protocol.ERR_BAD_CREDENTIALS.equals(error) ? "Sai tên đăng nhập hoặc mật khẩu" : "Đăng nhập thất bại: " + error;
            login.setBusy(false, msg);
        }
    }

    @Override
    public void onConnState(NetworkService.State s, String detail) {
        if (main != null) main.setConnState(s, detail);
        else if (login != null && s == NetworkService.State.RECONNECTING) login.setBusy(true, "Không kết nối được Server, đang thử lại...");
    }

    @Override
    public void onForceLogout(String reason) {
        core.shutdown();
        Alert a = new Alert(Alert.AlertType.WARNING, reason, javafx.scene.control.ButtonType.OK);
        a.setHeaderText("Bị đăng xuất");
        showLogin(reason);
        a.show();
    }

    @Override public void onConvListChanged() { if (main != null) main.refreshList(); }
    @Override public void onConvChanged(int id) { if (main != null) main.convChanged(id); }
    @Override public void onTyping(int id) { if (main != null) main.convChanged(id); }
    @Override public void onOpenConversation(int id) { if (main != null) main.openConversation(id); }
    @Override public void onSocial(Packet p) { if (main != null) main.onSocial(p); }

    @Override
    public void onToast(String msg) {
        if (main != null) main.toast(msg);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
