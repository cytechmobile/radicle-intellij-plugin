package network.radicle.jetbrains.radiclejetbrainsplugin.patches.review;

import com.google.protobuf.Any;
import com.intellij.collaboration.ui.CollaborationToolsUIUtilKt;
import com.intellij.collaboration.ui.SingleValueModel;
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil;
import com.intellij.collaboration.ui.codereview.ToggleableContainer;
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil;
import com.intellij.collaboration.ui.codereview.timeline.thread.TimelineThreadCommentsPanel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.text.HtmlBuilder;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import kotlin.Unit;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.icons.RadicleIcons;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.Emoji;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadDetails;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadDiscussion;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.Reaction;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.ThreadModel;
import network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline.EditablePanelHandler;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleCliService;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.EmojiPanel;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.MarkDownEditorPaneFactory;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.Utils;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.Utils.getVerticalPanel;

public class PatchReviewThreadComponentFactory {
    private static final String PATTERN_FORMAT = "dd/MM/yyyy HH:mm";
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(PATTERN_FORMAT).withZone(ZoneId.systemDefault());
    private static final int PADDING_LEFT = CodeReviewChatItemUIUtil.ComponentType.COMPACT.getContentLeftShift() + 12;
    private static final int PADDING_TOP_BOTTOM = 5;
    private static final String JPANEL_PREFIX_NAME = "COMMENT_";

    private final RadicleCliService cli;
    private final RadPatch patch;
    private final ObservableThreadModel threadsModel;
    private final Editor editor;
    private RadDetails radDetails;

    public PatchReviewThreadComponentFactory(RadPatch patch, ObservableThreadModel threadsModel, Editor editor) {
        this.cli = patch.project.getService(RadicleCliService.class);
        this.radDetails = cli.getCurrentIdentity();
        this.threadsModel = threadsModel;
        this.patch = patch;
        this.editor = editor;
    }

    public JComponent createThread(ThreadModel threadModel) {
        var verticalPanel = CollaborationToolsUIUtilKt.VerticalListPanel(0);
        CollectionListModel<RadDiscussion> listModel = new CollectionListModel<>();
        for (var discussion : threadModel.getRadDiscussion()) {
            listModel.add(discussion);
        }
        var commentsPanel = new TimelineThreadCommentsPanel<>(listModel, this::createComponent, 0, 10);
        verticalPanel.add(commentsPanel);
        verticalPanel.add(getThreadActionsComponent(threadModel));
        verticalPanel.setBorder(JBUI.Borders.empty(CodeReviewChatItemUIUtil.ComponentType.COMPACT.getInputPaddingInsets()));
        return CodeReviewCommentUIUtil.INSTANCE.createEditorInlayPanel(verticalPanel);
    }

    public boolean deleteComment(RadDiscussion disc) {
        var revisionId = patch.findRevisionId(disc.id);
        var res = cli.deletePatchComment(patch, revisionId, disc.id);
        return res != null;
    }

    public JComponent createUncollapsedThreadActionsComponent(ThreadModel threadModel) {
        var panelHandle = new EditablePanelHandler.PanelBuilder(patch.project, new JPanel(),
                RadicleBundle.message("reply", "Reply"),
                new SingleValueModel<>(""), (field) -> {
            var line = Integer.valueOf(threadModel.getLine());
            var latestRev = threadModel.getRadDiscussion().get(threadModel.getRadDiscussion().size() - 1);
            var location = new RadDiscussion.Location(threadsModel.getFilePath(), "ranges",
                    threadsModel.getCommitHash(), line, line);
            var revision = patch.findRevisionId(latestRev.id);
            boolean success = this.cli.createPatchComment(patch, revision, field.getText(), latestRev.id, location, field.getEmbedList());
            if (success) {
                threadsModel.update(patch);
            }
            return success;
        }).enableDragAndDrop(true).hideCancelAction(true).build();
        panelHandle.showAndFocusEditor();
        var builder = new CodeReviewChatItemUIUtil.Builder(CodeReviewChatItemUIUtil.ComponentType.COMPACT, integer ->
                new SingleValueModel<>(RadicleIcons.DEFAULT_AVATAR), panelHandle.panel);
        return builder.build();
    }

    public JComponent createCollapsedThreadActionComponent(ReplyAction replyAct) {
        var reply = new LinkLabel<Any>(RadicleBundle.message("reply", "Reply"), null) {
            @Override
            public void doClick() {
                replyAct.reply();
            }
        };
        var horizontalPanel = CollaborationToolsUIUtilKt.HorizontalListPanel(CodeReviewCommentUIUtil.Actions.HORIZONTAL_GAP);
        horizontalPanel.setBorder(JBUI.Borders.emptyLeft(PADDING_LEFT));
        horizontalPanel.add(reply);
        return horizontalPanel;
    }

    public JComponent getThreadActionsComponent(ThreadModel myThreadsModel) {
        var toggleModel = new SingleValueModel<>(false);
        return ToggleableContainer.INSTANCE.create(toggleModel, () -> {
            ReplyAction rep = () -> toggleModel.setValue(true);
            return createCollapsedThreadActionComponent(rep);
        }, () -> createUncollapsedThreadActionsComponent(myThreadsModel));
    }

    private JComponent createComponent(RadDiscussion disc) {
        final var verticalPanel = getVerticalPanel(5);
        var editorPane = new MarkDownEditorPaneFactory(disc.body, patch.project, patch.radProject.id, patch.repo.getRoot(), verticalPanel);
        var revisionId = patch.findRevisionId(disc.id);
        var isOutDated = !patch.isDiscussionBelongedToLatestRevision(disc);
        var panelHandle = new EditablePanelHandler.PanelBuilder(patch.project, editorPane.htmlEditorPane(),
                RadicleBundle.message("review.edit.comment"),
                new SingleValueModel<>(disc.body), (field) -> {
            RadPatch res = this.cli.editPatchComment(patch, revisionId, disc.id, field.getText(), field.getEmbedList());
            boolean success = res != null;
            if (success) {
                threadsModel.update(patch);
            }
            return success;
        }).enableDragAndDrop(true).build();
        var actionsPanel = CollaborationToolsUIUtilKt.HorizontalListPanel(CodeReviewCommentUIUtil.Actions.HORIZONTAL_GAP);
        radDetails = cli.getCurrentIdentity();
        var self = radDetails != null && disc.author.id.contains(radDetails.nodeId);
        if (self) {
            var editButton = CodeReviewCommentUIUtil.INSTANCE.createEditButton(e -> {
                panelHandle.showAndFocusEditor();
                return Unit.INSTANCE;
            });
            var deleteButton = CodeReviewCommentUIUtil.INSTANCE.createDeleteCommentIconButton(e -> {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    var success = this.deleteComment(disc);
                    if (success) {
                        threadsModel.update(patch);
                    }
                });
                return Unit.INSTANCE;
            });
            actionsPanel.add(editButton);
            actionsPanel.add(deleteButton);
        }
        var emojiPanel = new MyEmojiPanel(new SingleValueModel<>(patch), disc.reactions, disc.id, radDetails);
        var builder = new CodeReviewChatItemUIUtil.Builder(CodeReviewChatItemUIUtil.ComponentType.COMPACT, integer ->
                new SingleValueModel<>(RadicleIcons.DEFAULT_AVATAR), panelHandle.panel);
        var author = disc.author.generateLabelText(cli);
        var authorLink = HtmlChunk.link("#", author).wrapWith(HtmlChunk.font(ColorUtil.toHtmlColor(UIUtil.getLabelForeground()))).bold();
        var titleText = new HtmlBuilder().append(authorLink)
                .append(HtmlChunk.nbsp())
                .append(disc.timestamp != null ? DATE_TIME_FORMATTER.format(disc.timestamp) : "");
        if (isOutDated) {
            titleText.append(" (OUTDATED)");
        }
        builder.withHeader(new JLabel(MarkDownEditorPaneFactory.wrapHtml(titleText.toString())), actionsPanel);
        verticalPanel.add(builder.build());
        var emojiJPanel = emojiPanel.getEmojiPanel();
        emojiJPanel.setBorder(BorderFactory.createEmptyBorder(PADDING_TOP_BOTTOM, PADDING_LEFT, PADDING_TOP_BOTTOM, 0));
        verticalPanel.add(emojiJPanel);
        verticalPanel.setOpaque(false);
        verticalPanel.setName(JPANEL_PREFIX_NAME + disc.id);
        return verticalPanel;
    }

    private class MyEmojiPanel extends EmojiPanel<RadPatch> {

        protected MyEmojiPanel(SingleValueModel<RadPatch> model,
                               List<Reaction> reactions, String discussionId, RadDetails radDetails) {
            super(patch.project, model, reactions, discussionId, radDetails);
        }

        @Override
        public RadPatch addEmoji(Emoji emoji, String commentId) {
            return cli.patchCommentReact(patch, commentId, emoji.unicode(), true);
        }

        @Override
        public RadPatch removeEmoji(String emojiUnicode, String commentId) {
            return cli.patchCommentReact(patch, commentId, emojiUnicode, false);
        }

        @Override
        public void notifyEmojiChanges(String emojiUnicode, String commentId, boolean isAdded) {
            var updatedDiscussion = Utils.updateRadDiscussionModel(patch, emojiUnicode, commentId, radDetails, isAdded);
            if (updatedDiscussion != null) {
                updatePanel(updatedDiscussion);
            }
        }

        public void updatePanel(RadDiscussion discussion) {
            JPanel commentPanel = null;
            for (var component : editor.getContentComponent().getComponents()) {
                var timeLineCommentPanels = ((ArrayList) UIUtil.findComponentsOfType((JPanel) component, TimelineThreadCommentsPanel.class));
                if (timeLineCommentPanels.isEmpty()) {
                    continue;
                }
                var timelinePanels = (JPanel) timeLineCommentPanels.get(0);
                if (timelinePanels.getComponents().length == 0) {
                    continue;
                }
                var nestedComponents = ((JPanel) timelinePanels.getComponent(0)).getComponents();
                for (var child : nestedComponents) {
                    if (child.getName() != null && child.getName().equals(JPANEL_PREFIX_NAME + discussion.id)) {
                        commentPanel = (JPanel) child;
                        break;
                    }
                }
            }
            if (commentPanel != null) {
                commentPanel.removeAll();
                commentPanel.add(createComponent(discussion));
                commentPanel.revalidate();
                commentPanel.repaint();
            }
        }
    }

    public interface ReplyAction {
        void reply();
    }
}
