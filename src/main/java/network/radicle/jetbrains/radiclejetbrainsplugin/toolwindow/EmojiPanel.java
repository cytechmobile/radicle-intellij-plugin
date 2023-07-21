package network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow;

import com.intellij.collaboration.ui.SingleValueModel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.ui.AnimatedIcon;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.components.BorderLayoutPanel;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.Emoji;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadDetails;

import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.Utils.getHorizontalPanel;

public abstract class EmojiPanel<T> {
    private static final String FONT_NAME = "Segoe UI Emoji";
    private static final int FONT_STYLE = Font.PLAIN;
    private static final int FONT_SIZE = 14;
    private static final String SMILEY_FACE_EMOJI = "\uD83D\uDE00"; // 😀
    private final SingleValueModel<T> model;
    private final List<List<String>> reactions;
    private final String discussionId;
    private final RadDetails radDetails;
    private JBPopup reactorsPopUp;
    private JBPopup emojisPopUp;
    private JBPopupListener popupListener;

    protected EmojiPanel(SingleValueModel<T> model, List<List<String>> reactions, String discussionId,
                         RadDetails radDetails) {
        this.model = model;
        this.reactions = reactions;
        this.discussionId = discussionId;
        this.radDetails = radDetails;
    }

    private static class EmojiRender extends Utils.SelectionListCellRenderer<Emoji> {
        @Override
        public Component getListCellRendererComponent(JList<? extends Utils.SelectableWrapper<Emoji>> list,
                                                      Utils.SelectableWrapper<Emoji> selectableWrapperObj,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            var jLabel = new JLabel();
            jLabel.setFont(new Font(FONT_NAME, FONT_STYLE, FONT_SIZE));
            jLabel.setText(getText(selectableWrapperObj.value));
            return jLabel;
        }

        @Override
        public String getText(Emoji emoji) {
            return emoji.getUnicode();
        }

        @Override
        public String getPopupTitle() {
            return "";
        }
    }

    private static class ReactorRender extends Utils.SelectionListCellRenderer<String> {
        @Override
        public String getText(String value) {
            return value;
        }

        @Override
        public String getPopupTitle() {
            return "";
        }
    }

    private static CompletableFuture<List<Utils.SelectableWrapper<Emoji>>> getEmojis() {
        return CompletableFuture.supplyAsync(() -> {
            var selectableEmojiList = new ArrayList<Utils.SelectableWrapper<Emoji>>();
            var emojiList = Emoji.loadEmojis();
            for (var emoji : emojiList) {
                selectableEmojiList.add(new Utils.SelectableWrapper<>(emoji, false));
            }
            return selectableEmojiList;
        });
    }

    private static CompletableFuture<List<Utils.SelectableWrapper<String>>> getReactors(List<String> reactorsList) {
        return CompletableFuture.supplyAsync(() -> {
            var reactors = new ArrayList<Utils.SelectableWrapper<String>>();
            for (var reactor : reactorsList) {
                reactors.add(new Utils.SelectableWrapper<>(reactor, false));
            }
            return reactors;
        });
    }

    public JPanel getEmojiPanel() {
        var progressLabel = new JLabel(new AnimatedIcon.Default());
        progressLabel.setBorder(JBUI.Borders.empty(6, 0));
        progressLabel.setVisible(false);
        var emojiButton = new JLabel();
        emojiButton.setText(SMILEY_FACE_EMOJI);
        emojiButton.setFont(new Font(FONT_NAME, FONT_STYLE, FONT_SIZE));
        emojiButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        emojiButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                var result = new CompletableFuture<List<Emoji>>();
                emojisPopUp = Utils.PopupBuilder.createHorizontalPopup(getEmojis(), new EmojiRender(), result);
                popupListener = Utils.PopupBuilder.myListener;
                emojisPopUp.showUnderneathOf(emojiButton);
                result.thenComposeAsync(selectedEmoji -> {
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        ApplicationManager.getApplication().invokeLater(() ->
                                emojisPopUp.closeOk(null), ModalityState.any());
                        progressLabel.setVisible(true);
                        var res = selectedEmoji(selectedEmoji.get(0), discussionId);
                        progressLabel.setVisible(false);
                        var isSuccess = res != null;
                        if (isSuccess) {
                            model.setValue(model.getValue());
                        }
                    });
                    return null;
                });
            }
        });
        var borderPanel = new BorderLayoutPanel();
        borderPanel.setOpaque(false);
        borderPanel.addToLeft(emojiButton);
        var horizontalPanel = getHorizontalPanel(10);
        horizontalPanel.setOpaque(false);
        horizontalPanel.add(progressLabel);
        var groupReactions = groupEmojis(reactions);
        for (var emojiUnicode : groupReactions.keySet()) {
            var reactorEmoji = new JLabel(emojiUnicode);
            reactorEmoji.setFont(new Font(FONT_NAME, FONT_STYLE, FONT_SIZE));
            var reactorsPanel = new BorderLayoutPanel();
            reactorsPanel.setOpaque(false);
            reactorsPanel.addToLeft(reactorEmoji);
            var numberOfReactors = groupReactions.get(emojiUnicode).size();
            var self = isEmojiFromCurrentUser(radDetails.nodeId, groupReactions.get(emojiUnicode));
            var emojiReaction = new JLabel(String.valueOf(numberOfReactors));
            reactorsPanel.addToRight(emojiReaction);
            reactorsPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            if (self) {
                emojiReaction.setFont(new Font(FONT_NAME, Font.BOLD, 13));
                reactorsPanel.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        ApplicationManager.getApplication().executeOnPooledThread(() -> {
                            progressLabel.setVisible(true);
                            var res = unselectEmoji(emojiUnicode, discussionId);
                            var isSuccess = res != null;
                            if (isSuccess) {
                                model.setValue(model.getValue());
                            }
                            progressLabel.setVisible(false);
                        });
                    }
                });
            }
            var result = new CompletableFuture<List<String>>();
            reactorsPopUp = Utils.PopupBuilder.createHorizontalPopup(getReactors(groupReactions.get(emojiUnicode)),
                    new ReactorRender(), result);
            reactorsPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    reactorsPopUp = Utils.PopupBuilder.createHorizontalPopup(getReactors(groupReactions.get(emojiUnicode)),
                            new ReactorRender(), result);
                    reactorsPopUp.showUnderneathOf(reactorEmoji);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    reactorsPopUp.closeOk(null);
                }
            });
            horizontalPanel.add(reactorsPanel);
        }
        borderPanel.addToRight(horizontalPanel);
        return borderPanel;
    }

    private boolean isEmojiFromCurrentUser(String currentUserId, List<String> reactorsIds) {
        return reactorsIds.contains(currentUserId);
    }

    public JBPopupListener getPopupListener() {
        return popupListener;
    }

    public JBPopup getEmojisPopUp() {
        return emojisPopUp;
    }
    public Map<String, List<String>> groupEmojis(List<List<String>> myReactions) {
        HashMap<String, List<String>> map = new HashMap<>();
        for (var react : myReactions) {
            var from = react.get(0);
            var emoji = react.get(1);
            map.computeIfAbsent(emoji, k -> new ArrayList<>()).add(from);
        }
        return map;
    }

    public abstract T selectedEmoji(Emoji emoji, String id);

    public abstract T unselectEmoji(String emojiUnicode, String id);

}
