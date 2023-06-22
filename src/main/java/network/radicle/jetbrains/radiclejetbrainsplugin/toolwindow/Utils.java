package network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow;

import com.google.common.base.Strings;
import com.intellij.collaboration.ui.codereview.BaseHtmlEditorPane;
import com.intellij.collaboration.ui.codereview.timeline.StatusMessageComponentFactory;
import com.intellij.collaboration.ui.codereview.timeline.StatusMessageType;
import com.intellij.collaboration.ui.layout.SizeRestrictedSingleComponentLayout;
import com.intellij.ui.components.panels.ListLayout;
import git4idea.GitCommit;
import git4idea.history.GitHistoryUtils;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadDiscussion;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline.TimelineComponentFactory.createTimeLineItem;

public class Utils {
    private static final Logger logger = LoggerFactory.getLogger(Utils.class);
    private final RadPatch patch;

    public Utils(RadPatch patch) {
        this.patch = patch;
    }

    public Map<String, List<GitCommit>> calculateCommits() {
        var revisions = new HashMap<String, List<GitCommit>>();
        var success = fetchCommits();
        if (!success) {
            return null;
        }
        try {
            for (var rev : patch.revisions) {
                var patchCommits = GitHistoryUtils.history(patch.repo.getProject(),
                        patch.repo.getRoot(), rev.base() + "..." + rev.oid());
                revisions.put(rev.id(), patchCommits);
            }
            return revisions;
        } catch (Exception e) {
            logger.warn("error calculating patch commits for patch: {}", patch, e);
            return null;
        }
    }

    private boolean fetchCommits() {
        var service = patch.repo.getProject().getService(RadicleProjectService.class);
        var output = service.fetchPeerChanges(patch);
        return RadAction.isSuccess(output);
    }

    public static String findMessage(String replyTo, List<RadDiscussion> discussionList) {
        for (var com : discussionList) {
            if (com.id.equals(replyTo)) {
                return com.body;
            }
        }
        return "";
    }

    public static JComponent createCommentSection(List<RadDiscussion> discussionList) {
        var mainPanel = getVerticalPanel(0);
        for (var com : discussionList) {
            var textHtmlEditor = new BaseHtmlEditorPane();
            textHtmlEditor.setOpaque(false);
            var message = com.body;
            if (!Strings.isNullOrEmpty(com.replyTo)) {
                var replyToMessage = Utils.findMessage(com.replyTo, discussionList);
                message = "<div><div style=\"border-left: 2px solid black;\">" +
                        " <div style=\"margin-left:10px\">" + replyToMessage + "</div>\n" +
                        "</div><div style=\"margin-top:5px\">" + message + "</div></div>";
            }
            textHtmlEditor.setBody("<html><body>" + message + "</body></html>");
            var horizontalPanel = getHorizontalPanel(8);
            horizontalPanel.setOpaque(false);
            var contentPanel = new JPanel(SizeRestrictedSingleComponentLayout.Companion.constant(null, null));
            contentPanel.setOpaque(false);
            contentPanel.add(StatusMessageComponentFactory.INSTANCE.create(textHtmlEditor, StatusMessageType.WARNING));
            mainPanel.add(createTimeLineItem(contentPanel, horizontalPanel, com.author.id, com.timestamp));
        }
        return mainPanel;
    }

    public static JComponent getVerticalPanel(int gap) {
        return new JPanel(ListLayout.vertical(gap, ListLayout.Alignment.CENTER, ListLayout.GrowPolicy.GROW));
    }

    public static JComponent getHorizontalPanel(int gap) {
        return new JPanel(ListLayout.horizontal(gap, ListLayout.Alignment.START, ListLayout.GrowPolicy.GROW));
    }

}
