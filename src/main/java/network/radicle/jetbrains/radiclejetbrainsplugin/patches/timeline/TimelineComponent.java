package network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline;

import com.google.common.base.Strings;
import com.intellij.collaboration.ui.CollaborationToolsUIUtilKt;
import com.intellij.collaboration.ui.SingleValueModel;
import com.intellij.collaboration.ui.codereview.BaseHtmlEditorPane;
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil;
import com.intellij.collaboration.ui.codereview.CodeReviewTimelineUIUtil;
import com.intellij.collaboration.ui.codereview.CodeReviewTitleUIUtil;
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.panels.Wrapper;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadPatchComment;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadPatchEdit;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadSelf;
import network.radicle.jetbrains.radiclejetbrainsplugin.icons.RadicleIcons;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadDetails;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;

import javax.swing.*;

import static network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline.TimelineComponentFactory.createTimeLineItem;
import static network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline.TimelineComponentFactory.getHorizontalPanel;
import static network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline.TimelineComponentFactory.getVerticalPanel;

public class TimelineComponent {
    private final TimelineComponentFactory componentsFactory;
    private final RadPatch radPatch;
    private final SingleValueModel<RadPatch> radPatchModel;
    private BaseHtmlEditorPane headerTitle;

    public TimelineComponent(SingleValueModel<RadPatch> radPatchModel) {
        this.radPatchModel = radPatchModel;
        this.radPatch = radPatchModel.getValue();
        componentsFactory = new TimelineComponentFactory(radPatch);
    }

    public JComponent create() {
        var header = getHeader();
        var descriptionWrapper = new Wrapper();
        descriptionWrapper.setOpaque(false);
        descriptionWrapper.setContent(componentsFactory.createDescSection());

        var timelinePanel = getVerticalPanel(0);
        timelinePanel.setBorder(JBUI.Borders.empty(CodeReviewTimelineUIUtil.VERT_PADDING, 0));

        timelinePanel.setOpaque(false);
        timelinePanel.add(header);
        timelinePanel.add(descriptionWrapper);
        timelinePanel.add(componentsFactory.createRevisionSection());

        var horizontalPanel = getHorizontalPanel(8);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var radDetails = getCurrentRadDetails();
            if (radDetails != null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    var commentSection = createTimeLineItem(getCommentField().panel, horizontalPanel, radDetails.did, null);
                    timelinePanel.add(commentSection);
                }, ModalityState.any());
            }
        });

        var mainPanel = new Wrapper();
        var scrollPanel = ScrollPaneFactory.createScrollPane(timelinePanel, true);
        scrollPanel.setOpaque(false);

        mainPanel.setContent(scrollPanel);
        return mainPanel;
    }

    private RadDetails getCurrentRadDetails() {
        var radSelf = new RadSelf(radPatch.project);
        radSelf.askForIdentity(false);
        var output = radSelf.perform();
        if (RadAction.isSuccess(output)) {
            return new RadDetails(output.getStdoutLines(true));
        }
        return null;
    }

    public boolean createComment(String comment) {
        if (Strings.isNullOrEmpty(comment)) {
            return true;
        }
        var patchComment = new RadPatchComment(radPatch.repo, radPatch.id, comment);
        var out = patchComment.perform();
        final boolean success = RadAction.isSuccess(out);
        if (success) {
            radPatchModel.setValue(radPatch);
        }
        return true;
    }

    private EditablePanelHandler getCommentField() {
       var panelHandle = new EditablePanelHandler.PanelBuilder(radPatch.repo.getProject(), new JPanel(),
                RadicleBundle.message("patch.comment", "Comment"), new SingleValueModel<>(""), this::createComment)
                .hideCancelAction(true)
                .closeEditorAfterSubmit(false)
                .build();
        panelHandle.showAndFocusEditor();
        return panelHandle;
    }

    private JComponent getHeader() {
        final var title = CodeReviewTitleUIUtil.INSTANCE.createTitleText(radPatch.title, radPatch.id, "", "");

        headerTitle = new BaseHtmlEditorPane();
        headerTitle.setFont(JBFont.h2().asBold());
        headerTitle.setBody(title);

        var panelHandle = new EditablePanelHandler.PanelBuilder(radPatch.repo.getProject(), headerTitle,
                RadicleBundle.message("patch.proposal.change.title", "change title"), new SingleValueModel<>(radPatch.title), (editedTitle) -> {
            var patchEdit = new RadPatchEdit(radPatch.repo, radPatch.id, editedTitle);
            var out = patchEdit.perform();
            final boolean success = RadAction.isSuccess(out);
            if (success) {
                radPatchModel.setValue(radPatch);
            }
            return true;
        }).build();
        var contentPanel = panelHandle.panel;
        var actionsPanel = CollaborationToolsUIUtilKt.HorizontalListPanel(CodeReviewCommentUIUtil.Actions.HORIZONTAL_GAP);
        actionsPanel.add(CodeReviewCommentUIUtil.INSTANCE.createEditButton(e -> {
            panelHandle.showAndFocusEditor();
            return null;
        }));

        var b = new CodeReviewChatItemUIUtil.Builder(CodeReviewChatItemUIUtil.ComponentType.FULL,
                i -> new SingleValueModel<>(RadicleIcons.RADICLE), contentPanel);
        b.withHeader(contentPanel, actionsPanel);
        return b.build();
    }

    public BaseHtmlEditorPane getHeaderTitle() {
        return headerTitle;
    }

    public TimelineComponentFactory getComponentsFactory() {
        return componentsFactory;
    }
}
