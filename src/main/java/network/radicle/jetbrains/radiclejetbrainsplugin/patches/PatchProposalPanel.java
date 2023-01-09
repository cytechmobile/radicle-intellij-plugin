package network.radicle.jetbrains.radiclejetbrainsplugin.patches;

import com.intellij.collaboration.ui.CollaborationToolsUIUtil;
import com.intellij.collaboration.ui.SingleValueModel;
import com.intellij.collaboration.ui.codereview.BaseHtmlEditorPane;
import com.intellij.collaboration.ui.codereview.ReturnToListComponent;
import com.intellij.collaboration.ui.codereview.commits.CommitsBrowserComponentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.DiffPreview;
import com.intellij.openapi.vcs.changes.ui.CurrentBranchComponent;
import com.intellij.openapi.vcs.changes.ui.SimpleChangesBrowser;
import com.intellij.openapi.vcs.changes.ui.browser.LoadingChangesPanel;
import com.intellij.openapi.vcs.impl.ChangesBrowserToolWindow;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.SingleHeightTabs;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.components.BorderLayoutPanel;
import git4idea.GitCommit;
import git4idea.changes.GitChangeUtils;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepositoryManager;
import icons.CollaborationToolsIcons;
import kotlin.Unit;
import net.miginfocom.layout.CC;
import net.miginfocom.layout.LC;
import net.miginfocom.swing.MigLayout;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadRemote;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadTrack;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class PatchProposalPanel {
    private static final Logger logger = LoggerFactory.getLogger(PatchProposalPanel.class);
    protected RadPatch patch;
    protected final SingleValueModel<List<Change>> patchChanges = new SingleValueModel<>(List.of());
    protected final SingleValueModel<List<GitCommit>> patchCommits = new SingleValueModel<>(List.of());
    protected TabInfo commitTab ;
    protected TabInfo filesTab ;

    public JComponent createViewPatchProposalPanel(PatchTabController controller, RadPatch patch, Project project) {
        this.patch = patch;
        this.patchChanges.setValue(List.of());
        this.patchCommits.setValue(List.of());

        calculatePatchCommits();

        final var uiDisposable = Disposer.newDisposable(controller.getDisposer(), "RadiclePatchProposalDetailsPanel");
        var infoComponent = createInfoComponent(project);
        var tabInfo = new TabInfo(infoComponent);
        tabInfo.setText(RadicleBundle.message("info"));
        tabInfo.setSideComponent(createReturnToListSideComponent(controller));

        var filesComponent = createFilesComponent(controller);
        var filesInfo = new TabInfo(filesComponent);
        filesTab = filesInfo;
        filesInfo.setText(RadicleBundle.message("files"));
        filesInfo.setSideComponent(createReturnToListSideComponent(controller));

        var commitComponent = createCommitComponent(controller);
        var commitInfo = new TabInfo(commitComponent);
        commitTab = commitInfo;
        commitInfo.setText(RadicleBundle.message("commits"));
        commitInfo.setSideComponent(createReturnToListSideComponent(controller));

        var tabs = new SingleHeightTabs(null, uiDisposable);
        tabs.addTab(tabInfo);
        tabs.addTab(filesInfo);
        tabs.addTab(commitInfo);
        return tabs;
    }

    protected JComponent createReturnToListSideComponent(PatchTabController controller) {
        return ReturnToListComponent.INSTANCE.createReturnToListSideComponent(RadicleBundle.message("backToList"),
                () -> {controller.createPatchesPanel(); return Unit.INSTANCE;});
    }

    protected JComponent createCommitComponent(PatchTabController controller) {
        final var project = patch.repo.getProject();
        var simpleChangesTree = getChangesBrowser(patch.repo.getProject(), controller);
        final var splitter = new OnePixelSplitter(true, "Radicle.PatchProposals.Commits.Component", 0.4f);
        splitter.setOpaque(true);
        splitter.setBackground(UIUtil.getListBackground());
        final SingleValueModel<List<Change>> selectedCommitChanges = new SingleValueModel<>(List.of());
        var commitBrowser = new CommitsBrowserComponentBuilder(project, (SingleValueModel) patchCommits)
                .setEmptyCommitListText(RadicleBundle.message("patchProposalNoCommits"))
                .onCommitSelected(c -> {
                    if (c == null || ! (c instanceof GitCommit gc)) {
                        selectedCommitChanges.setValue(List.of());
                    } else {
                        selectedCommitChanges.setValue(new ArrayList<>(gc.getChanges()));
                    }
                    simpleChangesTree.setChangesToDisplay(selectedCommitChanges.getValue());
                    return Unit.INSTANCE;
                })
                .create();
        splitter.setFirstComponent(commitBrowser);
        splitter.setSecondComponent(simpleChangesTree);
        return splitter;
    }

    protected JComponent createInfoComponent(Project project) {
        var branchPanel = branchComponent(project);
        var titlePanel = titleComponent();
        var descriptionPanel = descriptionComponent();

        var detailsSection = new JPanel(new MigLayout(new LC()
                .insets("0", "0", "0", "0").gridGap("0", "0").fill().flowY()));
        detailsSection.setOpaque(false);
        detailsSection.setBorder(JBUI.Borders.empty(8));

        detailsSection.add(branchPanel, new CC().gapBottom(String.valueOf(UI.scale(8))));
        detailsSection.add(titlePanel, new CC().gapBottom(String.valueOf(UI.scale(8))));
        detailsSection.add(descriptionPanel, new CC().gapBottom(String.valueOf(UI.scale(8))));

        var borderPanel = new JPanel(new BorderLayout());
        borderPanel.add(detailsSection, BorderLayout.NORTH);

        return borderPanel;
    }

    private JComponent descriptionComponent() {
        var titlePane = new BaseHtmlEditorPane();
        titlePane.setFont(titlePane.getFont().deriveFont((float) (titlePane.getFont().getSize() * 1.2)));
        titlePane.setBody("Lorem Ipsum is simply dummy text of the printing and typesetting industry. Lorem Ipsum has " +
                "been the industry's standard dummy text ever since the 1500s," +
                " when an unknown printer took a galley of type and scrambled it to make a type specimen book.");
        var nonOpaquePanel = new NonOpaquePanel(new MigLayout(new LC().insets("0").gridGap("0", "0").noGrid()));
        nonOpaquePanel.add(titlePane, new CC());
        return nonOpaquePanel;
    }

    private JComponent titleComponent() {
        var icon = new JLabel();
        icon.setIcon(CollaborationToolsIcons.PullRequestOpen);
        var titlePane = new BaseHtmlEditorPane();
        titlePane.setFont(titlePane.getFont().deriveFont((float) (titlePane.getFont().getSize() * 1.2)));
        titlePane.setBody(patch.branchName + " - " + patch.commitHash);
        var nonOpaquePanel = new NonOpaquePanel(new MigLayout(new LC().insets("0").gridGap("0", "0").noGrid()));
        nonOpaquePanel.add(icon, new CC().gapRight(String.valueOf(JBUIScale.scale(4))));
        nonOpaquePanel.add(titlePane, new CC());
        return nonOpaquePanel;
    }

    private JComponent branchComponent(Project project) {
        var branchPanel = new NonOpaquePanel();
        branchPanel.setLayout(new MigLayout(new LC().fillX().gridGap("0","0").insets("0","0","0","0")));;
        var currentBranch = "";
        var gitRepoManager = GitRepositoryManager.getInstance(project);
        if (gitRepoManager.getRepositories().size() > 0) {
            currentBranch = gitRepoManager.getRepositories().get(0).getCurrentBranch().getName();
        }
        var to = createLabel(currentBranch);
        var from = createLabel(patch.branchName + " - " + patch.commitHash);
        branchPanel.add(to, new CC().minWidth(Integer.toString(JBUIScale.scale(30))));
        var arrowLabel = new JLabel(UIUtil.leftArrow());
        arrowLabel.setForeground(CurrentBranchComponent.TEXT_COLOR);
        arrowLabel.setBorder(JBUI.Borders.empty(0,5));
        branchPanel.add(arrowLabel);
        branchPanel.add(from, new CC().minWidth(Integer.toString(JBUIScale.scale(30))));
        return branchPanel;
    }

    private JBLabel createLabel(String branchName) {
        var label = new JBLabel(CollaborationToolsIcons.Review.Branch);
        CollaborationToolsUIUtil.INSTANCE.overrideUIDependentProperty(label, jbLabel -> {
            jbLabel.setForeground(CurrentBranchComponent.TEXT_COLOR);
            jbLabel.setBackground(CurrentBranchComponent.getBranchPresentationBackground(UIUtil.getListBackground()));
            jbLabel.andOpaque();
            return null;
        });
        label.setText(branchName);
        return label;
    }

    protected JComponent createFilesComponent(PatchTabController controller) {
        var simpleChangesTree = getChangesBrowser(patch.repo.getProject(), controller);
        var radLoadingChangePanel = new LoadingChangesPanel(simpleChangesTree, simpleChangesTree.getViewer().getEmptyText(), controller.getDisposer());
        radLoadingChangePanel.loadChangesInBackground(() -> {
            var countDown = new CountDownLatch(1);
            calculatePatchChanges(countDown);
            try {
                countDown.await();
            } catch (Exception e) {
                logger.warn("Unable to calculate patch changes");
                return false;
            }
            return true;
        }, success -> simpleChangesTree.setChangesToDisplay(Boolean.TRUE.equals(success) ? patchChanges.getValue() : List.of()));
        var panel = new BorderLayoutPanel().withBackground(UIUtil.getListBackground());
        return panel.addToCenter(ScrollPaneFactory.createScrollPane(radLoadingChangePanel, true));
    }

    protected SimpleChangesBrowser getChangesBrowser(Project project, PatchTabController controller) {
        var simpleChangesTree = new SimpleChangesBrowser(patch.repo.getProject(),List.of());
        DiffPreview diffPreview = ChangesBrowserToolWindow.createDiffPreview(project, simpleChangesTree, controller.getDisposer());
        simpleChangesTree.setShowDiffActionPreview(diffPreview);
        return simpleChangesTree;
    }

    protected void calculatePatchChanges(CountDownLatch latch) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            //first make sure that the peer is tracked
            boolean ok = checkAndTrackPeerIfNeeded(patch);
            if (!ok) {
                latch.countDown();
                return;
            }
            var diff = GitChangeUtils.getDiff(patch.repo, patch.repo.getCurrentRevision(), patch.commitHash, true);
            final List<Change> changes = diff == null ? Collections.emptyList() : new ArrayList<>(diff);
            ApplicationManager.getApplication().invokeLater(() -> {
                patchChanges.setValue(changes);
                filesTab.append(" " + changes.size(),new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY));
                latch.countDown();
            });
        });
    }

    protected void calculatePatchCommits() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var current = patch.repo.getCurrentRevision();
            try {
                final var history = GitHistoryUtils.history(patch.repo.getProject(), patch.repo.getRoot(),
                        patch.commitHash + "..." + current);
                logger.info("calculated history for patch: {} - ({}..{}) {}", patch, patch.commitHash, current, history);
                ApplicationManager.getApplication().invokeLater(() -> {
                    patchCommits.setValue(history);
                    commitTab.append(" " + patchCommits.getValue().size(),new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY));
                });
            } catch (Exception e) {
                logger.warn("error calculating patch commits for patch: {}", patch, e);
                RadAction.showErrorNotification(patch.repo.getProject(),
                        RadicleBundle.message("radCliError" ),
                        RadicleBundle.message("errorCalculatingPatchProposalCommits"));
            }
        });
    }

    protected boolean checkAndTrackPeerIfNeeded(RadPatch patch) {
        if (patch.self) {
            return true;
        }
        final var trackedPeers = new RadRemote(patch.repo).findTrackedPeers();
        if (trackedPeers != null && !trackedPeers.isEmpty()) {
            var tracked = trackedPeers.stream().filter(p -> p.id().equals(patch.peerId)).findAny().orElse(null);
            if (tracked != null) {
                return true;
            }
        }

        var trackPeer = new RadTrack(patch.repo, new RadTrack.Peer(patch.peerId));
        var out = trackPeer.perform();
        return RadTrack.isSuccess(out);
    }
}
