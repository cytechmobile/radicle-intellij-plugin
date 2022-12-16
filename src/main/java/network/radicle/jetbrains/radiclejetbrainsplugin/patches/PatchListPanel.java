package network.radicle.jetbrains.radiclejetbrainsplugin.patches;

import com.intellij.execution.process.ProcessOutput;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.util.ProgressWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ListUtil;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.ScrollingUtil;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ListUiUtil;
import com.intellij.vcs.log.ui.frame.ProgressStripe;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import net.miginfocom.layout.CC;
import net.miginfocom.layout.LC;
import net.miginfocom.swing.MigLayout;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadTrack;
import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleSettingsHandler;
import network.radicle.jetbrains.radiclejetbrainsplugin.dialog.clone.CloneRadDialog;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.SeedNode;
import org.jdesktop.swingx.VerticalLayout;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class PatchListPanel {
    private final Project project;
    private final ComboBox<SeedNode> seedNodeComboBox;
    private final DefaultListModel<RadPatch> patchModel;
    private final RadicleSettingsHandler radicleSettingsHandler;
    private boolean triggerSeedNodeAction = true;
    private final CoroutineScope scope;
    private List<RadPatch> loadedRadPatches;
    private ProgressStripe progressStripe;
    private final PatchSearchPanelViewModel searchVm;

    private JBList patchesList;
    public PatchListPanel(Project project, CoroutineScope scope) {
        this.project = project;
        this.patchModel = new DefaultListModel<>();
        this.radicleSettingsHandler = new RadicleSettingsHandler();
        this.seedNodeComboBox = new ComboBox<>();
        this.scope = scope;
        searchVm = new PatchSearchPanelViewModel(scope, new PatchSearchHistoryModel(), project);
    }

    private void initializeSeedNodeCombobox() {
        var settings = radicleSettingsHandler.loadSettings();
        var loadedSeedNodes = settings.getSeedNodes();
        seedNodeComboBox.removeAllItems();
        for (var node : loadedSeedNodes) {
            seedNodeComboBox.addItem(node);
        }
    }

    public JComponent create() {
        initializeSeedNodeCombobox();
        var filterPanel = new PatchFilterPanel(searchVm).create(scope);
        var seedNodePanel = createSeedNodePanel();
        var verticalPanel = new JPanel(new VerticalLayout(5));
        verticalPanel.add(filterPanel);
        verticalPanel.add(seedNodePanel);
        var mainPanel = JBUI.Panels.simplePanel();
        var listPanel = createListPanel();
        mainPanel.addToTop(verticalPanel);
        mainPanel.addToCenter(listPanel);


       searchVm.getSearchState().collect((patchListSearchValue, continuation) -> {
           filterList(patchListSearchValue);
           return null;
       }, new Continuation<Object>() {
           @Override
           public void resumeWith(@NotNull Object o) {

           }

           @NotNull
           @Override
           public CoroutineContext getContext() {
               return scope.getCoroutineContext();
           }
       });
        updateListPanel();
        return mainPanel;
    }

    private void updateListEmptyText(PatchListSearchValue patchListSearchValue) {
        patchesList.getEmptyText().clear();
        if (loadedRadPatches.isEmpty() || patchModel.isEmpty()) {
            patchesList.getEmptyText().setText("Nothing found");
        }
        if (patchListSearchValue.getFilterCount() > 0) {
            patchesList.getEmptyText().appendSecondaryText("Clear filters", SimpleTextAttributes.LINK_ATTRIBUTES,
                    e -> searchVm.getSearchState().setValue(new PatchListSearchValue()));
        }
    }

    private JPanel createSeedNodePanel() {
        var borderPanel = new JPanel(new BorderLayout(5, 5));
        Presentation presentation = new Presentation();
        presentation.setIcon(AllIcons.Actions.BuildAutoReloadChanges);
        borderPanel.add(new ActionButton(new RefreshSeedNodeAction(), presentation,
                ActionPlaces.UNKNOWN, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE), BorderLayout.EAST);
        seedNodeComboBox.setRenderer(new CloneRadDialog.SeedNodeCellRenderer());
        seedNodeComboBox.addActionListener(e -> {
            if (seedNodeComboBox.getSelectedItem() != null && triggerSeedNodeAction) {
                updateListPanel();
            }
        });
        borderPanel.add(seedNodeComboBox, BorderLayout.CENTER);
        return borderPanel;
    }

    private JPanel createListPanel() {
        patchesList = new JBList<>(patchModel);
        patchesList.setCellRenderer(new PatchListCellRenderer());
        patchesList.setExpandableItemsEnabled(false);
        patchesList.getEmptyText().clear();
        patchesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ScrollingUtil.installActions(patchesList);
        ListUtil.installAutoSelectOnMouseMove(patchesList);
        ListUiUtil.Selection.INSTANCE.installSelectionOnFocus(patchesList);
        ListUiUtil.Selection.INSTANCE.installSelectionOnRightClick(patchesList);

        var scrollPane = ScrollPaneFactory.createScrollPane(patchesList, true);
        progressStripe = new ProgressStripe(scrollPane, Disposer.newDisposable(), ProgressWindow.DEFAULT_PROGRESS_DIALOG_POSTPONE_TIME_MILLIS);
        return progressStripe;
    }

    private void filterList(PatchListSearchValue patchListSearchValue) {
        patchModel.clear();
        if (loadedRadPatches == null) {
            return ;
        }
        if (patchListSearchValue.getFilterCount() == 0) {
            patchModel.addAll(loadedRadPatches);
        }
        updateListEmptyText(patchListSearchValue);
    }

    private List<RadPatch> getPatchProposals(List<GitRepository> repos, String url) {
        var outputs = new ArrayList<RadPatch>();
        var radInitializedRepos = RadAction.getInitializedReposWithNodeConfigured(repos, true);
        if (radInitializedRepos.isEmpty()) {
        return List.of();
        }
        final var updateCountDown = new CountDownLatch(radInitializedRepos.size());
        for (GitRepository repo : radInitializedRepos) {
            var pull = new RadTrack(repo, url);
            ProcessOutput output = pull.perform(updateCountDown);
            if (output.getExitCode() == 0) {
                var radPatch = parsePatchProposals(repo, output);
                if (!radPatch.isEmpty()) {
                    outputs.addAll(radPatch);
                }
            }
        }
        return outputs;
    }

    private List<RadPatch> parsePatchProposals(GitRepository repo, ProcessOutput output) {
        var infos = output.getStdoutLines(true);
        var radPatches = new ArrayList<RadPatch>();
        for (String info : infos) {
            var parts = info.split(" ");
            if (parts.length == 2 || info.contains("you")) {
                radPatches.add(new RadPatch(parts[1], repo));
            }
        }
        return radPatches;
    }

    private void updateListPanel() {
        var selectedSeedNode = (SeedNode) seedNodeComboBox.getSelectedItem();
        if (selectedSeedNode == null) {
            return ;
        }
        var url = "http://" + selectedSeedNode.host;
        var gitRepoManager = GitRepositoryManager.getInstance(project);
        var repos = gitRepoManager.getRepositories();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            patchModel.clear();
            progressStripe.startLoading();
            var patchProposals = getPatchProposals(repos, url);
            loadedRadPatches = patchProposals;
            ApplicationManager.getApplication().invokeLater(() -> {
                patchModel.addAll(patchProposals);
                progressStripe.stopLoading();
                updateListEmptyText(searchVm.getSearchState().getValue());
            });
        });
    }

    private class RefreshSeedNodeAction extends AnAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            triggerSeedNodeAction = false;
            var prevSelectedIndex = seedNodeComboBox.getSelectedIndex();
            initializeSeedNodeCombobox();
            seedNodeComboBox.setSelectedIndex(prevSelectedIndex);
            triggerSeedNodeAction = true;
            updateListPanel();
        }
    }

    private static class PatchListCellRenderer extends JPanel implements ListCellRenderer<RadPatch> {
        private final JLabel title = new JLabel();
        private final JPanel patchPanel;
        public PatchListCellRenderer() {
            var gapAfter = JBUI.scale(5);
            patchPanel = new JPanel();
            patchPanel.setOpaque(false);
            patchPanel.setBorder(JBUI.Borders.empty(10, 8));
            patchPanel.setLayout(new MigLayout(new LC().gridGap(gapAfter + "px", "0")
                    .insets("0", "0", "0", "0")
                    .fillX()));

            this.setLayout(new MigLayout(new LC().gridGap(gapAfter + "px", "0").noGrid()
                    .insets("0", "0", "0", "0")
                    .fillX()));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends RadPatch> list,
                                                      RadPatch value, int index, boolean isSelected, boolean cellHasFocus) {
            setBackground(ListUiUtil.WithTallRow.INSTANCE.background(list, isSelected, list.hasFocus()));
            var primaryTextColor = ListUiUtil.WithTallRow.INSTANCE.foreground(isSelected, list.hasFocus());
            title.setText(value.repo.getRoot().getName() + " - " + value.peerId);
            title.setForeground(primaryTextColor);
            patchPanel.add(title);
            add(patchPanel, new CC().minWidth("0").gapAfter("push"));
            return this;
        }
    }

}
