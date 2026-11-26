package com.zalo.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zalo.client.core.ClientCore;
import com.zalo.client.core.ConvModel;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Các hộp thoại: Danh bạ (tìm người / kết bạn / nhắn tin), Tạo nhóm, Thông tin nhóm (quản trị). */
final class Dialogs {
    private Dialogs() {}

    // ================================================================== Danh bạ
    static void contacts(ClientCore core, MainView main) {
        Stage st = new Stage();
        st.setTitle("Danh bạ");

        // --- Tab tìm kiếm
        TextField q = new TextField();
        q.setPromptText("Tên đăng nhập / số điện thoại / tên hiển thị (>= 2 ký tự)");
        Button go = new Button("Tìm");
        ObservableList<JsonObject> found = FXCollections.observableArrayList();
        ListView<JsonObject> foundList = new ListView<>(found);
        foundList.setCellFactory(v -> new UserCell(core, st, true));
        Runnable search = () -> core.searchUsers(q.getText().trim());
        go.setOnAction(e -> search.run());
        q.setOnAction(e -> search.run());
        HBox bar = new HBox(6, q, go);
        HBox.setHgrow(q, Priority.ALWAYS);
        VBox searchBox = new VBox(8, bar, foundList);
        searchBox.setPadding(new Insets(10));
        VBox.setVgrow(foundList, Priority.ALWAYS);

        // --- Tab bạn bè + lời mời
        ObservableList<JsonObject> friendItems = FXCollections.observableArrayList();
        ListView<JsonObject> friendList = new ListView<>(friendItems);
        friendList.setCellFactory(v -> new UserCell(core, st, false));
        VBox friendBox = new VBox(8, friendList);
        friendBox.setPadding(new Insets(10));
        VBox.setVgrow(friendList, Priority.ALWAYS);

        TabPane tabs = new TabPane(new Tab("Tìm kiếm", searchBox), new Tab("Bạn bè & lời mời", friendBox));
        tabs.getTabs().forEach(t -> t.setClosable(false));

        main.socialSink = p -> {
            if (Protocol.SEARCH_RESULT.equals(p.type())) {
                found.clear();
                for (JsonElement e : p.array("users")) found.add(e.getAsJsonObject());
            } else if (Protocol.FRIEND_LIST_RESULT.equals(p.type())) {
                friendItems.clear();
                for (JsonElement e : p.array("pending")) {
                    JsonObject o = e.getAsJsonObject();
                    o.addProperty("friendStatus", "INCOMING");
                    friendItems.add(o);
                }
                for (JsonElement e : p.array("friends")) {
                    JsonObject o = e.getAsJsonObject();
                    o.addProperty("friendStatus", "ACCEPTED");
                    friendItems.add(o);
                }
            }
        };
        st.setOnHidden(e -> main.socialSink = null);
        core.friendList();
        st.setScene(new Scene(tabs, 460, 520));
        st.show();
    }

    /** Một dòng người dùng với nút hành động tùy trạng thái bạn bè. */
    private static final class UserCell extends ListCell<JsonObject> {
        private final ClientCore core;
        private final Stage owner;
        private final boolean fromSearch;

        UserCell(ClientCore core, Stage owner, boolean fromSearch) {
            this.core = core;
            this.owner = owner;
            this.fromSearch = fromSearch;
        }

        @Override
        protected void updateItem(JsonObject u, boolean empty) {
            super.updateItem(u, empty);
            if (empty || u == null) {
                setGraphic(null);
                return;
            }
            int id = u.get("userId").getAsInt();
            String name = u.get("displayName").getAsString();
            String status = u.has("friendStatus") ? u.get("friendStatus").getAsString() : "NONE";
            Label l = new Label(name + "  (@" + u.get("username").getAsString() + ")" + (u.get("online").getAsBoolean() ? "  🟢" : ""));
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox row = new HBox(6, UiUtil.avatar(name, id, 16), l, sp);
            row.setAlignment(Pos.CENTER_LEFT);

            Button chat = new Button("Nhắn tin");
            chat.setOnAction(e -> { core.openPrivate(id); owner.close(); });
            row.getChildren().add(chat);
            if ("NONE".equals(status)) {
                Button add = new Button("Kết bạn");
                add.setOnAction(e -> { core.friendRequest(id); add.setDisable(true); add.setText("Đã gửi"); });
                row.getChildren().add(add);
            } else if ("PENDING".equals(status)) {
                row.getChildren().add(new Label("Đang chờ"));
            } else if ("INCOMING".equals(status)) {
                Button ok = new Button("Chấp nhận");
                ok.setOnAction(e -> { core.friendAccept(id); ok.setDisable(true); });
                row.getChildren().add(ok);
            }
            setGraphic(row);
        }
    }

    // ================================================================== Tạo nhóm
    static void createGroup(ClientCore core, MainView main) {
        core.friendList();
        Stage st = new Stage();
        st.setTitle("Tạo nhóm mới");
        TextField name = new TextField();
        name.setPromptText("Tên nhóm");
        ListView<JsonObject> friends = new ListView<>();
        friends.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        friends.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(JsonObject o, boolean empty) {
                super.updateItem(o, empty);
                setText(empty || o == null ? null : o.get("displayName").getAsString() + " (@" + o.get("username").getAsString() + ")");
            }
        });
        for (JsonElement e : main.friends) friends.getItems().add(e.getAsJsonObject());
        main.socialSink = p -> {
            if (Protocol.FRIEND_LIST_RESULT.equals(p.type())) {
                friends.getItems().clear();
                for (JsonElement e : p.array("friends")) friends.getItems().add(e.getAsJsonObject());
            }
        };
        st.setOnHidden(e -> main.socialSink = null);
        Label hint = new Label("Chọn bạn bè (giữ Ctrl để chọn nhiều):");
        Button create = new Button("Tạo nhóm");
        create.setOnAction(e -> {
            List<Integer> ids = new ArrayList<>();
            for (JsonObject o : friends.getSelectionModel().getSelectedItems()) ids.add(o.get("userId").getAsInt());
            if (name.getText().isBlank() || ids.isEmpty()) {
                hint.setText("Cần nhập tên nhóm và chọn ít nhất 1 người bạn!");
                return;
            }
            core.createGroup(name.getText().trim(), ids);
            st.close();
        });
        VBox box = new VBox(8, name, hint, friends, create);
        box.setPadding(new Insets(12));
        VBox.setVgrow(friends, Priority.ALWAYS);
        st.setScene(new Scene(box, 380, 460));
        st.show();
    }

    // ================================================================== Thông tin nhóm
    static void groupInfo(ClientCore core, MainView main, ConvModel c) {
        Stage st = new Stage();
        st.setTitle("Nhóm: " + c.title(core.myId));
        ConvModel.Member me = c.member(core.myId);
        boolean admin = me != null && "ADMIN".equals(me.role);

        ListView<ConvModel.Member> members = new ListView<>(FXCollections.observableArrayList(c.members));
        members.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(ConvModel.Member m, boolean empty) {
                super.updateItem(m, empty);
                setText(empty || m == null ? null : m.displayName + ("ADMIN".equals(m.role) ? "  ★ Quản trị" : "") + (m.online ? "  🟢" : ""));
            }
        });
        Button rename = new Button("Đổi tên");
        Button add = new Button("Thêm thành viên");
        Button remove = new Button("Xóa khỏi nhóm");
        Button leave = new Button("Rời nhóm");
        rename.setDisable(!admin);
        add.setDisable(!admin);
        remove.setDisable(!admin);

        rename.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog(c.groupName);
            d.setHeaderText("Tên nhóm mới");
            d.showAndWait().ifPresent(n -> { if (!n.isBlank()) core.groupRename(c.id, n.trim()); st.close(); });
        });
        add.setOnAction(e -> {
            core.friendList();
            List<JsonObject> candidates = new ArrayList<>();
            for (JsonElement f : main.friends) {
                JsonObject o = f.getAsJsonObject();
                if (c.member(o.get("userId").getAsInt()) == null) candidates.add(o);
            }
            if (candidates.isEmpty()) {
                main.toast("Không còn bạn bè nào để thêm");
                return;
            }
            List<String> names = new ArrayList<>();
            for (JsonObject o : candidates) names.add(o.get("displayName").getAsString() + " (#" + o.get("userId").getAsInt() + ")");
            ChoiceDialog<String> d = new ChoiceDialog<>(names.get(0), names);
            d.setHeaderText("Chọn người cần thêm");
            Optional<String> r = d.showAndWait();
            r.ifPresent(s -> { core.groupAdd(c.id, candidates.get(names.indexOf(s)).get("userId").getAsInt()); st.close(); });
        });
        remove.setOnAction(e -> {
            ConvModel.Member m = members.getSelectionModel().getSelectedItem();
            if (m != null && m.userId != core.myId) { core.groupRemove(c.id, m.userId); st.close(); }
        });
        leave.setOnAction(e -> { core.groupLeave(c.id); st.close(); });

        HBox btns = new HBox(6, rename, add, remove, leave);
        VBox box = new VBox(8, new Label(c.members.size() + " thành viên"), members, btns);
        box.setPadding(new Insets(12));
        VBox.setVgrow(members, Priority.ALWAYS);
        st.setScene(new Scene(box, 460, 420));
        st.show();
    }
}
