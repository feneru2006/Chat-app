package com.zalo.client.ui;

import com.zalo.client.core.ClientCore;
import com.zalo.client.core.ConvModel;
import com.zalo.client.core.MsgModel;
import com.zalo.client.media.AudioRecorder;
import com.zalo.common.MessageStateCalc.State;
import com.zalo.common.Protocol;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Khung chat một hội thoại. Hiển thị: Sender (avatar + tên màu), Time, State (✓ / ✓✓ / Đã xem),
 * Image (thumbnail), Video, Sound, File, Emoji, Reaction, Thu hồi / Xóa phía tôi, "Đang soạn tin".
 */
public final class ChatPane extends BorderPane {
    private final ClientCore core;
    private final ConvModel conv;
    private final MainView main;
    private final ListView<MsgModel> list = new ListView<>();
    private final Label title = new Label();
    private final Label sub = new Label();
    private final Button olderBtn = new Button("↑ Tải tin cũ hơn");
    private final TextField input = new TextField();
    private final EmojiPicker emoji;
    private final AudioRecorder recorder = new AudioRecorder();
    private boolean recording;
    private Stage activeVideoStage; 
    private static final Map<String, Image> VIDEO_THUMBS = new java.util.concurrent.ConcurrentHashMap<>();

    // Đang soạn tin: debounce 500ms mới báo "đang gõ", 3s không gõ thì báo "ngừng".
    private final PauseTransition debounce = new PauseTransition(Duration.millis(500));
    private final PauseTransition idle = new PauseTransition(Duration.seconds(3));
    private boolean typingSent;

    public ChatPane(ClientCore core, ConvModel conv, MainView main) {
        this.core = core;
        this.conv = conv;
        this.main = main;

        // ---- header
        title.setStyle("-fx-font-weight:bold;-fx-font-size:16px;");
        sub.setStyle("-fx-text-fill:#777;-fx-font-size:12px;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(10, UiUtil.avatar(conv.title(core.myId), conv.isGroup() ? conv.id : conv.peer(core.myId).userId, 20),
                new VBox(2, title, sub), sp, olderBtn);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(10));
        head.setStyle("-fx-border-color:#ddd;-fx-border-width:0 0 1 0;");
        if (conv.isGroup()) {
            Button info = new Button("⚙ Nhóm");
            info.setOnAction(e -> Dialogs.groupInfo(core, main, conv));
            head.getChildren().add(info);
        }
        Button clear = new Button("🗑");
        clear.setTooltip(new javafx.scene.control.Tooltip("Xóa lịch sử trò chuyện (chỉ phía tôi)"));
        clear.setOnAction(e -> core.clearHistory(conv.id));
        head.getChildren().add(clear);
        olderBtn.setOnAction(e -> core.loadOlder(conv));
        setTop(head);

        // ---- danh sách tin
        list.setItems(conv.messages);
        list.setFocusTraversable(false);
        list.setStyle("-fx-background-color:white;");
        list.setCellFactory(v -> new MsgCell());
        list.skinProperty().addListener((o, a, b) -> Platform.runLater(this::hookScroll));
        setCenter(list);

        // ---- thanh nhập
        emoji = new EmojiPicker(e -> { input.appendText(e); input.requestFocus(); input.end(); });
        Button emojiBtn = new Button("😀");
        emojiBtn.setOnAction(e -> emoji.toggle(emojiBtn));
        Button attach = new Button("📎");
        attach.setOnAction(e -> pickFile());
        Button mic = new Button("🎤");
        mic.setTooltip(new javafx.scene.control.Tooltip("Nhấn giữ để ghi âm, thả để gửi"));
        mic.setOnMousePressed(e -> startRecord(mic));
        mic.setOnMouseReleased(e -> stopRecord(mic));
        Button send = new Button("Gửi");
        send.setStyle("-fx-background-color:#0068ff;-fx-text-fill:white;");
        input.setPromptText("Nhập tin nhắn...");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> doSend());
        send.setOnAction(e -> doSend());
        HBox bar = new HBox(6, emojiBtn, attach, mic, input, send);
        bar.setPadding(new Insets(8));
        bar.setAlignment(Pos.CENTER);
        bar.setStyle("-fx-border-color:#ddd;-fx-border-width:1 0 0 0;");
        setBottom(bar);

        // ---- typing
        input.textProperty().addListener((o, a, text) -> {
            if (text == null || text.isEmpty()) return;
            debounce.playFromStart();
            idle.playFromStart();
        });
        debounce.setOnFinished(e -> {
            if (!typingSent) { typingSent = true; core.sendTyping(conv.id, true); }
        });
        idle.setOnFinished(e -> {
            if (typingSent) { typingSent = false; core.sendTyping(conv.id, false); }
        });

        updateHeader();
    }

    // ------------------------------------------------------------------ vòng đời hiển thị
    public void onOpened() {
        updateHeader();
        Platform.runLater(() -> {
            if (!conv.messages.isEmpty()) list.scrollTo(conv.messages.size() - 1);
            input.requestFocus();
        });
    }

    /** Gọi mỗi khi dữ liệu hội thoại đổi (tin mới, trạng thái, đang soạn, P2P...). */
    public void onChanged() {
        updateHeader();
        int n = conv.messages.size();
        switch (conv.changeKind) {
            case ConvModel.KIND_APPEND -> { list.refresh(); if (n > 0) list.scrollTo(n - 1); }
            case ConvModel.KIND_INITIAL -> { list.refresh(); Platform.runLater(() -> { if (n > 0) list.scrollTo(n - 1); }); }
            case ConvModel.KIND_PREPEND -> { list.refresh(); int added = conv.addedCount; Platform.runLater(() -> list.scrollTo(Math.max(0, added))); }
            default -> list.refresh();
        }
    }

    private void updateHeader() {
        title.setText(conv.title(core.myId));
        String s;
        long now = System.currentTimeMillis();
        StringBuilder who = new StringBuilder();
        for (Map.Entry<Integer, Long> e : conv.typing.entrySet()) {
            if (e.getValue() > now) {
                ConvModel.Member m = conv.member(e.getKey());
                if (who.length() > 0) who.append(", ");
                who.append(m == null ? "?" : m.displayName);
            }
        }
        if (who.length() > 0) {
            s = who + " đang soạn tin...";
        } else if (conv.isGroup()) {
            s = conv.members.size() + " thành viên";
        } else {
            ConvModel.Member p = conv.peer(core.myId);
            s = p != null && p.online ? "Đang hoạt động • kênh: " + (core.p2pConnected(conv) ? "P2P trực tiếp" : "qua Server") : (p == null ? "Ngoại tuyến" : UiUtil.ago(p.lastSeen));
        }
        sub.setText(s);
        olderBtn.setVisible(conv.hasMore && conv.historyLoaded);
        olderBtn.setManaged(conv.hasMore && conv.historyLoaded);
    }

    /** Cuộn lên sát đầu danh sách => tự tải 30 tin cũ hơn (History / Pagination). */
    private void hookScroll() {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                sb.valueProperty().addListener((o, a, v) -> {
                    if (v.doubleValue() <= 0.001 && conv.historyLoaded) core.loadOlder(conv);
                });
            }
        }
    }

    // ------------------------------------------------------------------ gửi
    private void doSend() {
        String t = input.getText();
        if (t == null || t.isBlank()) return;
        core.sendText(conv.id, t.trim());
        input.clear();
        typingSent = false;
        debounce.stop();
        idle.stop();
    }

    private void pickFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Chọn ảnh / video (.mp4) / tệp");
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f != null) core.sendMedia(conv.id, f, ClientCore.typeOf(f), 0);
    }

    private void startRecord(Button mic) {
        if (recording) return;
        try {
            recorder.start();
            recording = true;
            mic.setText("⏺");
            mic.setStyle("-fx-background-color:#e53935;-fx-text-fill:white;");
        } catch (Exception ex) {
            main.toast("Không mở được micro: " + ex.getMessage());
        }
    }

    private void stopRecord(Button mic) {
        if (!recording) return;
        recording = false;
        mic.setText("🎤");
        mic.setStyle("");
        try {
            File wav = new File(new File(core.cfg.get("download.dir", "downloads"), "voice"), "voice_" + System.currentTimeMillis() + ".wav");
            AudioRecorder.Result r = recorder.stop(wav);
            if (r == null) main.toast("Ghi âm quá ngắn (< 1 giây)");
            else core.sendMedia(conv.id, r.wav(), Protocol.MSG_SOUND, r.durationSec());
        } catch (IOException ex) {
            main.toast("Lỗi ghi âm: " + ex.getMessage());
        }
    }

    // ================================================================== ô tin nhắn
    private final class MsgCell extends ListCell<MsgModel> {
        MsgCell() {
            setStyle("-fx-background-color:transparent;-fx-padding:2 8;");
        }

        @Override
        protected void updateItem(MsgModel m, boolean empty) {
            super.updateItem(m, empty);
            if (empty || m == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setGraphic(build(m, getIndex()));
            setContextMenu(menuFor(m));
            setPrefWidth(0);                                    // để ListView không sinh thanh cuộn ngang
        }
    }

    private Node build(MsgModel m, int idx) {
        if ("SYSTEM".equals(m.type)) {
            Label l = new Label(m.content);
            l.setStyle("-fx-text-fill:#777;-fx-font-size:12px;-fx-background-color:#f2f2f2;-fx-padding:2 10;-fx-background-radius:10;");
            HBox h = new HBox(l);
            h.setAlignment(Pos.CENTER);
            return h;
        }
        boolean mine = m.senderId == core.myId;
        MsgModel prev = idx > 0 && idx - 1 < conv.messages.size() ? conv.messages.get(idx - 1) : null;
        boolean newRun = prev == null || prev.senderId != m.senderId || "SYSTEM".equals(prev.type)
                || m.createdAt - prev.createdAt > 120_000;                     // gom tin cùng người trong 2 phút

        VBox col = new VBox(2);
        col.setMaxWidth(420);
        if (!mine && conv.isGroup() && newRun) {                               // Sender: tên màu theo user_id
            Label n = new Label(m.senderName);
            n.setStyle("-fx-font-weight:bold;-fx-font-size:12px;-fx-text-fill:" + UiUtil.css(UiUtil.userColor(m.senderId)) + ";");
            col.getChildren().add(n);
        }
        VBox bubble = new VBox(4, body(m));
        bubble.setPadding(new Insets(7, 11, 7, 11));
        bubble.setStyle("-fx-background-radius:12;-fx-background-color:" + (mine ? "#d6e8ff" : "#f0f0f0") + ";");
        col.getChildren().add(bubble);

        // reactions
        if (!m.reactions.isEmpty()) {
            Map<String, Integer> cnt = new LinkedHashMap<>();
            m.reactions.values().forEach(r -> cnt.merge(r, 1, Integer::sum));
            StringBuilder sb = new StringBuilder();
            cnt.forEach((r, c) -> sb.append(reactionEmoji(r)).append(c > 1 ? c : "").append(' '));
            Label rl = new Label(sb.toString().trim());
            rl.setStyle("-fx-font-size:12px;");
            col.getChildren().add(rl);
        }

        // meta: Time + kênh + State
        StringBuilder meta = new StringBuilder(UiUtil.time(m.createdAt));
        if (mine) {
            if (!conv.isGroup() && !m.isMedia()) meta.append(m.viaP2P ? " • P2P" : " • Server");
            State st = core.stateOf(conv, m);
            meta.append("  ").append(switch (st) {
                case SENDING -> "🕓 Đang gửi";
                case SENT -> "✓ Đã gửi";
                case DELIVERED -> "✓✓ Đã nhận";
                case READ -> "✓✓ Đã xem";
            });
        } else if (!conv.isGroup() && !m.isMedia() && m.viaP2P) {
            meta.append(" • P2P");
        }
        Label metaL = new Label(meta.toString());
        metaL.setStyle("-fx-text-fill:#999;-fx-font-size:10px;");
        col.getChildren().add(metaL);

        // "Đã xem bởi ..." ở tin cuối cùng của mình
        if (mine && isLastOwn(m)) {
            var readers = core.readersOf(conv, m);
            if (!readers.isEmpty()) {
                Label r = new Label("Đã xem" + (conv.isGroup() ? " bởi " + String.join(", ", readers) : ""));
                r.setStyle("-fx-text-fill:#0068ff;-fx-font-size:10px;");
                col.getChildren().add(r);
            }
        }

        HBox row = new HBox(8);
        if (mine) {
            row.setAlignment(Pos.TOP_RIGHT);
            row.getChildren().add(col);
        } else {
            row.setAlignment(Pos.TOP_LEFT);
            Node av = newRun ? UiUtil.avatar(m.senderName, m.senderId, 16) : new Region();
            if (!newRun) ((Region) av).setMinWidth(32);
            row.getChildren().addAll(av, col);
        }
        return row;
    }

    private boolean isLastOwn(MsgModel m) {
        for (int i = conv.messages.size() - 1; i >= 0; i--) {
            MsgModel x = conv.messages.get(i);
            if (x.senderId == core.myId && x.id > 0) return x == m;
        }
        return false;
    }

    private static String reactionEmoji(String r) {
        return switch (r) {
            case "LIKE" -> "👍";
            case "HEART" -> "❤️";
            case "HAHA" -> "😆";
            case "SAD" -> "😢";
            case "ANGRY" -> "😡";
            default -> r;
        };
    }

    // ------------------------------------------------------------------ nội dung từng loại
    private Node body(MsgModel m) {
        if (m.recalled) {
            Label l = new Label("Tin nhắn đã bị thu hồi");
            l.setStyle("-fx-text-fill:#999;-fx-font-style:italic;");
            return l;
        }
        VBox box = new VBox(4);
        switch (m.type) {
            case "IMAGE" -> box.getChildren().add(imageNode(m));
            case "VIDEO" -> box.getChildren().add(videoNode(m));
            case "SOUND" -> box.getChildren().add(soundNode(m));
            case "FILE" -> box.getChildren().add(fileNode(m));
            default -> {
                Label t = new Label(m.content);
                t.setWrapText(true);
                t.setMaxWidth(390);
                t.setStyle("-fx-font-size:14px;");
                box.getChildren().add(t);
            }
        }
        if (m.progress >= 0) {                                   // đang upload / download
            ProgressBar pb = new ProgressBar(m.progress);
            pb.setPrefWidth(200);
            HBox h = new HBox(6, pb, new Label((int) (m.progress * 100) + "%"));
            h.setAlignment(Pos.CENTER_LEFT);
            if (m.senderId == core.myId && m.id <= 0) {
                Button cancel = new Button("Hủy");
                cancel.setOnAction(e -> core.cancelUpload(m));
                h.getChildren().add(cancel);
            }
            box.getChildren().add(h);
        }
        if (m.failed) {
            Label f = new Label("⚠ Gửi thất bại");
            f.setStyle("-fx-text-fill:#d33;");
            box.getChildren().add(f);
        }
        return box;
    }

    /** Image: hiển thị THUMBNAIL (300x300 ~20KB); chỉ tải ảnh gốc khi bấm phóng to. */
    private Node imageNode(MsgModel m) {
        ImageView iv = new ImageView();
        iv.setPreserveRatio(true);
        iv.setFitWidth(220);
        iv.setStyle("-fx-cursor:hand;");
        Label ph = new Label("Đang tải ảnh...");
        StackPane sp = new StackPane(ph, iv);
        if (m.localFile != null && m.localFile.exists()) {
            iv.setImage(new Image(m.localFile.toURI().toString(), 300, 300, true, true, true));
            ph.setVisible(false);
        } else if (m.hasThumb()) {
            core.fetch(m, true, f -> {
                if (f != null) { iv.setImage(new Image(f.toURI().toString())); ph.setVisible(false); }
                else ph.setText("Không tải được ảnh");
            });
        } else {
            ph.setText("🖼 " + m.fileName());
        }
        sp.setOnMouseClicked(e -> core.fetch(m, false, f -> {
            if (f == null) return;
            Stage st = new Stage();
            ImageView big = new ImageView(new Image(f.toURI().toString()));
            big.setPreserveRatio(true);
            big.setFitWidth(900);
            big.setFitHeight(650);
            st.setTitle(m.fileName());
            st.setScene(new Scene(new StackPane(big)));
            st.show();
        }));
        return sp;
    }

/** Video: tự động trích khung hình đầu làm ảnh bìa (có Cache) + chống mở trùng nhiều cửa sổ. */
    private Node videoNode(MsgModel m) {
        ImageView thumbView = new ImageView();
        thumbView.setPreserveRatio(true);
        thumbView.setFitWidth(240);
        thumbView.setFitHeight(135);

        Label play = new Label("▶");
        play.setStyle("-fx-text-fill:white;-fx-font-size:34px;-fx-background-color:rgba(0,0,0,0.45);-fx-padding:4 14;-fx-background-radius:30;");
        Label name = new Label(m.fileName() + " (" + UiUtil.size(m.fileSize()) + ")");
        name.setStyle("-fx-text-fill:white;-fx-font-size:11px;-fx-background-color:rgba(0,0,0,0.55);-fx-padding:2 6;-fx-background-radius:4;");
        name.setMaxWidth(228);

        VBox overlay = new VBox(8, play, name);
        overlay.setAlignment(Pos.CENTER);

        StackPane cover = new StackPane(thumbView, overlay);
        cover.setPrefSize(240, 135);
        cover.setStyle("-fx-background-color:#222;-fx-background-radius:8;-fx-cursor:hand;");

        // 1. Tải ảnh bìa từ Cache hoặc trích xuất 1 lần duy nhất
        loadVideoThumb(m, thumbView);

        // 2. Chống bấm mở 2 lần cùng lúc
        final boolean[] opening = {false};

        cover.setOnMouseClicked(e -> {
            if (opening[0]) return;
            if (activeVideoStage != null && activeVideoStage.isShowing()) {
                activeVideoStage.toFront();
                return;
            }
            opening[0] = true;

            core.fetch(m, false, f -> {
                opening[0] = false;
                if (f == null) return;

                String key = (m.localFile != null && m.localFile.exists())
                        ? m.localFile.getAbsolutePath()
                        : "msg_" + m.id;
                if (thumbView.getImage() == null) {
                    grabFirstFrameJavaFX(key, f, thumbView);
                }

                Media media = new Media(f.toURI().toString());
                MediaPlayer mp = new MediaPlayer(media);
                MediaView mv = new MediaView(mp);
                mv.setFitWidth(720);
                mv.setPreserveRatio(true);

                Button btnPlay = new Button("⏸");
                Slider slider = new Slider(0, 100, 0);
                HBox.setHgrow(slider, Priority.ALWAYS);
                Label timeLbl = new Label("0:00 / 0:00");
                timeLbl.setStyle("-fx-text-fill:white;-fx-font-size:12px;");

                btnPlay.setOnAction(ev -> {
                    if (mp.getStatus() == MediaPlayer.Status.PLAYING) {
                        mp.pause();
                        btnPlay.setText("▶");
                    } else {
                        mp.play();
                        btnPlay.setText("⏸");
                    }
                });

                mp.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
                    if (!slider.isValueChanging() && mp.getTotalDuration() != null && mp.getTotalDuration().toSeconds() > 0) {
                        double pct = newTime.toSeconds() / mp.getTotalDuration().toSeconds() * 100.0;
                        slider.setValue(pct);
                        timeLbl.setText(UiUtil.duration((int) newTime.toSeconds()) + " / " + UiUtil.duration((int) mp.getTotalDuration().toSeconds()));
                    }
                });

                slider.setOnMouseReleased(ev -> {
                    if (mp.getTotalDuration() != null) {
                        mp.seek(mp.getTotalDuration().multiply(slider.getValue() / 100.0));
                    }
                });

                mp.setOnEndOfMedia(() -> {
                    mp.seek(Duration.ZERO);
                    mp.pause();
                    btnPlay.setText("▶");
                });

                HBox controls = new HBox(10, btnPlay, slider, timeLbl);
                controls.setAlignment(Pos.CENTER);
                controls.setPadding(new Insets(8, 12, 8, 12));
                controls.setStyle("-fx-background-color:#181818;");

                BorderPane root = new BorderPane();
                root.setCenter(new StackPane(mv));
                root.setBottom(controls);
                root.setStyle("-fx-background-color:black;");

                Stage st = new Stage();
                activeVideoStage = st;
                st.setTitle(m.fileName());
                st.setScene(new Scene(root, 720, 460));
                st.setOnHidden(ev -> {
                    mp.dispose();
                    if (activeVideoStage == st) activeVideoStage = null;
                });
                mp.setAutoPlay(true);
                st.show();
            });
        });
        return cover;
    }

    /** Lấy ảnh bìa từ Cache trước; nếu chưa có mới giải mã 1 lần duy nhất và lưu vào Cache. */
    private void loadVideoThumb(MsgModel m, ImageView thumbView) {
        String key = (m.localFile != null && m.localFile.exists())
                ? m.localFile.getAbsolutePath()
                : "msg_" + m.id;

        Image cached = VIDEO_THUMBS.get(key);
        if (cached != null) {
            thumbView.setImage(cached);
            return;
        }

        if (m.localFile != null && m.localFile.exists()) {
            grabFirstFrameJavaFX(key, m.localFile, thumbView);
        } else if (m.hasThumb()) {
            core.fetch(m, true, f -> {
                if (f != null) {
                    Image img = new Image(f.toURI().toString());
                    VIDEO_THUMBS.put(key, img);
                    thumbView.setImage(img);
                }
            });
        } else if (m.id > 0) {
            core.fetch(m, false, f -> {
                if (f != null) grabFirstFrameJavaFX(key, f, thumbView);
            });
        }
    }

    /** Chờ MediaPlayer thực sự chạy qua mốc 120ms (GPU đã vẽ xong frame) mới chụp snapshot và lưu Cache. */
    private void grabFirstFrameJavaFX(String cacheKey, File videoFile, ImageView target) {
        Image existing = VIDEO_THUMBS.get(cacheKey);
        if (existing != null) {
            target.setImage(existing);
            return;
        }
        try {
            MediaPlayer tmpPlayer = new MediaPlayer(new Media(videoFile.toURI().toString()));
            MediaView tmpView = new MediaView(tmpPlayer);
            tmpView.setFitWidth(240);
            tmpView.setPreserveRatio(true);
            tmpPlayer.setMute(true);

            final boolean[] captured = {false};
            tmpPlayer.currentTimeProperty().addListener((obs, oldT, newT) -> {
                if (!captured[0] && newT.toMillis() >= 120) {
                    captured[0] = true;
                    WritableImage snap = tmpView.snapshot(new SnapshotParameters(), null);
                    if (snap != null && snap.getWidth() > 0) {
                        VIDEO_THUMBS.put(cacheKey, snap);
                        target.setImage(snap);
                    }
                    tmpPlayer.stop();
                    tmpPlayer.dispose();
                }
            });

            tmpPlayer.setOnReady(tmpPlayer::play);
            tmpPlayer.setOnError(tmpPlayer::dispose);
        } catch (Exception ignored) { }
    }

    /** Sound: nút ▶/⏸ + thanh kéo tua tiến trình trực tiếp trên bong bóng chat + thời lượng. */
    private Node soundNode(MsgModel m) {
        Button play = new Button("▶");
        Slider slider = new Slider(0, 100, 0);
        slider.setPrefWidth(130);
        Label dur = new Label(UiUtil.duration(m.durationSec()));
        dur.setStyle("-fx-font-size:12px;");

        final MediaPlayer[] holder = new MediaPlayer[1];

        play.setOnAction(e -> {
            if (holder[0] != null) {
                if (holder[0].getStatus() == MediaPlayer.Status.PLAYING) {
                    holder[0].pause();
                    play.setText("▶");
                } else {
                    holder[0].play();
                    play.setText("⏸");
                }
                return;
            }
            core.fetch(m, false, f -> {
                if (f == null) return;
                MediaPlayer mp = new MediaPlayer(new Media(f.toURI().toString()));
                holder[0] = mp;

                mp.currentTimeProperty().addListener((obs, oldT, newT) -> {
                    if (!slider.isValueChanging() && mp.getTotalDuration() != null && mp.getTotalDuration().toSeconds() > 0) {
                        slider.setValue(newT.toSeconds() / mp.getTotalDuration().toSeconds() * 100.0);
                        dur.setText(UiUtil.duration((int) newT.toSeconds()) + " / " + UiUtil.duration(m.durationSec()));
                    }
                });

                slider.setOnMouseReleased(ev -> {
                    if (mp.getTotalDuration() != null) {
                        mp.seek(mp.getTotalDuration().multiply(slider.getValue() / 100.0));
                    }
                });

                mp.setOnEndOfMedia(() -> {
                    mp.seek(Duration.ZERO);
                    mp.pause();
                    play.setText("▶");
                    slider.setValue(0);
                    dur.setText(UiUtil.duration(m.durationSec()));
                });

                play.setText("⏸");
                mp.play();
            });
        });

        HBox h = new HBox(8, play, slider, dur);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Node fileNode(MsgModel m) {
        Label l = new Label("📎 " + m.fileName() + "  (" + UiUtil.size(m.fileSize()) + ")");
        Button dl = new Button("Tải về");
        dl.setOnAction(e -> core.fetch(m, false, f -> {
            if (f == null) return;
            try {
                File dest = new File(core.cfg.get("download.dir", "downloads"), m.fileName());
                dest.getParentFile().mkdirs();
                if (!f.equals(dest)) Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                main.toast("Đã lưu: " + dest.getAbsolutePath());
            } catch (IOException ex) {
                main.toast("Lỗi lưu file: " + ex.getMessage());
            }
        }));
        HBox h = new HBox(8, l, dl);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    // ------------------------------------------------------------------ menu chuột phải
    private ContextMenu menuFor(MsgModel m) {
        ContextMenu cm = new ContextMenu();
        if (m.content != null && !m.recalled && "TEXT".equals(m.type)) {
            MenuItem copy = new MenuItem("Sao chép");
            copy.setOnAction(e -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(m.content);
                Clipboard.getSystemClipboard().setContent(cc);
            });
            cm.getItems().add(copy);
        }
        if (m.id > 0 && !m.recalled) {
            Menu react = new Menu("Thả cảm xúc");
            for (String r : new String[]{"LIKE", "HEART", "HAHA", "SAD", "ANGRY"}) {
                MenuItem it = new MenuItem(reactionEmoji(r) + " " + r);
                it.setOnAction(e -> core.react(m, r));
                react.getItems().add(it);
            }
            MenuItem none = new MenuItem("Gỡ cảm xúc");
            none.setOnAction(e -> core.react(m, null));
            react.getItems().add(none);
            cm.getItems().add(react);
        }
        if (m.senderId == core.myId && m.id > 0 && !m.recalled) {
            MenuItem recall = new MenuItem("Thu hồi (với mọi người)");
            recall.setOnAction(e -> core.recall(m));
            cm.getItems().add(recall);
        }
        if (m.id > 0) {
            MenuItem del = new MenuItem("Xóa ở phía tôi");
            del.setOnAction(e -> core.deleteForMe(m));
            cm.getItems().add(del);
        }
        return cm.getItems().isEmpty() ? null : cm;
    }
}
