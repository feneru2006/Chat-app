package com.zalo.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.zalo.client.core.ClientCore;
import com.zalo.client.core.ConvModel;
import com.zalo.client.net.NetworkService;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Màn hình chính: danh sách hội thoại bên trái, khung chat bên phải. */
public final class MainView extends BorderPane {
    private final ClientCore core;
    private final ListView<ConvModel> list = new ListView<>();
    private final ObservableList<ConvModel> items = FXCollections.observableArrayList();
    private final StackPane center = new StackPane();
    private final Map<Integer, ChatPane> panes = new HashMap<>();
    private final Label conn = new Label();
    private final Label toast = new Label();
    private boolean suppress;

    /** Hộp thoại đang mở (Danh bạ...) đăng ký ở đây để nhận SEARCH_RESULT / FRIEND_LIST_RESULT. */
    public Consumer<Packet> socialSink;
    public JsonArray friends = new JsonArray();

    public MainView(ClientCore core) {
        this.core = core;

        Label me = new Label(core.myName);
        me.setStyle("-fx-font-weight:bold;-fx-font-size:15px;");
        Button contacts = new Button("🔍 Danh bạ");
        Button newGroup = new Button("👥 Nhóm mới");
        contacts.setOnAction(e -> Dialogs.contacts(core, this));
        newGroup.setOnAction(e -> Dialogs.createGroup(core, this));
        HBox buttons = new HBox(6, contacts, newGroup);
        conn.setStyle("-fx-font-size:11px;-fx-text-fill:#1a8f3c;");
        conn.setText("● Đã kết nối");
        VBox top = new VBox(6, me, conn, buttons);
        top.setPadding(new Insets(10));

        list.setItems(items);
        list.setCellFactory(v -> new ConvCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> {
            if (!suppress && sel != null) openConversation(sel.id);
        });

        VBox left = new VBox(top, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPrefWidth(300);
        left.setStyle("-fx-border-color:#ddd;-fx-border-width:0 1 0 0;");

        Label hint = new Label("Chọn một hội thoại để bắt đầu chat");
        hint.setStyle("-fx-text-fill:#888;-fx-font-size:15px;");
        center.getChildren().add(hint);

        toast.setStyle("-fx-background-color:#333;-fx-text-fill:white;-fx-padding:6 12;-fx-background-radius:6;");
        toast.setVisible(false);
        StackPane centerWrap = new StackPane(center, toast);
        StackPane.setAlignment(toast, Pos.TOP_CENTER);
        StackPane.setMargin(toast, new Insets(10));

        setLeft(left);
        setCenter(centerWrap);
        refreshList();
    }

    // ------------------------------------------------------------------ danh sách
    public void refreshList() {
        List<ConvModel> sorted = new ArrayList<>(core.convs.values());
        sorted.sort(Comparator.comparingLong((ConvModel c) -> c.updatedAt).reversed());
        suppress = true;
        items.setAll(sorted);
        ConvModel cur = core.convs.get(core.currentConv);
        if (cur != null) list.getSelectionModel().select(cur);
        suppress = false;
        list.refresh();
    }

    public void openConversation(int convId) {
        core.openConversation(convId);
        ConvModel c = core.convs.get(convId);
        if (c == null) return;
        ChatPane pane = panes.computeIfAbsent(convId, id -> new ChatPane(core, c, this));
        center.getChildren().setAll(pane);
        pane.onOpened();
        suppress = true;
        list.getSelectionModel().select(c);
        suppress = false;
    }

    public void convChanged(int convId) {
        ChatPane p = panes.get(convId);
        if (p != null) p.onChanged();
    }

    // ------------------------------------------------------------------ trạng thái mạng / thông báo
    public void setConnState(NetworkService.State s, String detail) {
        switch (s) {
            case ONLINE -> { conn.setText("● Đã kết nối"); conn.setStyle("-fx-font-size:11px;-fx-text-fill:#1a8f3c;"); }
            case RECONNECTING, CONNECTING -> {
                conn.setText("○ Đang kết nối lại... " + (detail == null ? "" : detail));
                conn.setStyle("-fx-font-size:11px;-fx-text-fill:#d97706;");
            }
            default -> { conn.setText("○ Mất kết nối"); conn.setStyle("-fx-font-size:11px;-fx-text-fill:#d33;"); }
        }
    }

    public void toast(String msg) {
        toast.setText(msg);
        toast.setVisible(true);
        PauseTransition t = new PauseTransition(Duration.seconds(3));
        t.setOnFinished(e -> toast.setVisible(false));
        t.play();
    }

    public void onSocial(Packet p) {
        switch (p.type()) {
            case Protocol.FRIEND_LIST_RESULT -> friends = p.array("friends") == null ? new JsonArray() : p.array("friends");
            case Protocol.FRIEND_REQUEST_IN -> {
                toast(p.object("from").get("displayName").getAsString() + " gửi lời mời kết bạn");
                core.friendList();
            }
            case Protocol.FRIEND_ACCEPTED -> {
                toast(p.object("user").get("displayName").getAsString() + " đã là bạn bè");
                core.friendList();
            }
            default -> { }
        }
        if (socialSink != null) socialSink.accept(p);
    }

    // ------------------------------------------------------------------ ô hội thoại
    private final class ConvCell extends ListCell<ConvModel> {
        @Override
        protected void updateItem(ConvModel c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                return;
            }
            String title = c.title(core.myId);
            ConvModel.Member peer = c.isGroup() ? null : c.peer(core.myId);
            Label name = new Label(title + (peer != null && peer.online ? "  🟢" : ""));
            name.setStyle("-fx-font-weight:bold;");
            Label prev = new Label(c.preview);
            prev.setStyle("-fx-text-fill:#777;-fx-font-size:12px;");
            prev.setMaxWidth(190);
            VBox text = new VBox(2, name, prev);
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox row = new HBox(8, UiUtil.avatar(title, c.isGroup() ? c.id : (peer == null ? 0 : peer.userId), 20), text, sp);
            row.setAlignment(Pos.CENTER_LEFT);
            if (c.unread > 0) {
                Label badge = new Label(String.valueOf(c.unread));
                badge.setStyle("-fx-background-color:#e53935;-fx-text-fill:white;-fx-background-radius:10;-fx-padding:1 7;-fx-font-size:11px;");
                row.getChildren().add(badge);
            }
            setGraphic(row);
        }
    }
}
