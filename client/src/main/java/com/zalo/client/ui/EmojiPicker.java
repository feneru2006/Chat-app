package com.zalo.client.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.stage.Popup;

import java.util.function.Consumer;

/** Bảng chọn Emoji (tiêu chí Emoji). Emoji là chuỗi UTF-8 4-byte, đi qua Socket/MySQL utf8mb4 nguyên vẹn. */
public final class EmojiPicker {
    private static final String[] EMOJIS = {
            "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎", "🤔", "😴", "😭", "😡",
            "👍", "👎", "👏", "🙏", "💪", "🎉", "🔥", "❤️", "💔", "💯", "✅", "❌",
            "🌹", "🎂", "🍕", "☕", "⚽", "🚀", "🐶", "🐱", "🌞", "🌙", "⭐", "💡"};

    private final Popup popup = new Popup();

    public EmojiPicker(Consumer<String> onPick) {
        GridPane g = new GridPane();
        g.setHgap(2);
        g.setVgap(2);
        g.setStyle("-fx-background-color:white;-fx-border-color:#bbb;-fx-padding:6;");
        for (int i = 0; i < EMOJIS.length; i++) {
            String e = EMOJIS[i];
            Button b = new Button(e);
            b.setStyle("-fx-background-color:transparent;-fx-font-size:18px;");
            b.setOnAction(ev -> onPick.accept(e));
            g.add(b, i % 12, i / 12);
        }
        popup.getContent().add(g);
        popup.setAutoHide(true);
    }

    public void toggle(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor.getScene().getWindow(), b.getMinX() - 20, b.getMinY() - 150);
    }
}
