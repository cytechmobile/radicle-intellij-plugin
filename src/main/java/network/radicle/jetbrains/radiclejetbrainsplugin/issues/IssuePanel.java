package network.radicle.jetbrains.radiclejetbrainsplugin.issues;

import com.google.common.base.Strings;
import com.intellij.collaboration.ui.SingleValueModel;
import com.intellij.collaboration.ui.codereview.ReturnToListComponent;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.SingleHeightTabs;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import kotlin.Unit;
import net.miginfocom.layout.CC;
import net.miginfocom.layout.LC;
import net.miginfocom.swing.MigLayout;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadIssue;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleProjectApi;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.Utils;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JLabel;
import java.awt.BorderLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class IssuePanel {
    protected RadIssue issue;
    protected SingleValueModel<RadIssue> issueModel;
    protected TabInfo infoTab;
    protected IssueTabController issueTabController;
    private final RadicleProjectApi api;
    private final AssigneesSelect assigneesSelect;
    private final StateSelect stateSelect;
    private final TagSelect tagSelect;
    private static final String PATTERN_FORMAT = "dd/MM/yyyy HH:mm";
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(PATTERN_FORMAT).withZone(ZoneId.systemDefault());
    private static final String AUTHOR_SPLIT_CHARACTER = ":";

    public IssuePanel(IssueTabController issueTabController, SingleValueModel<RadIssue> issueModel) {
        this.issueTabController = issueTabController;
        this.issueModel = issueModel;
        this.issue = issueModel.getValue();
        this.api = issue.project.getService(RadicleProjectApi.class);
        this.assigneesSelect = new AssigneesSelect();
        this.stateSelect = new StateSelect();
        this.tagSelect = new TagSelect();
    }

    public JComponent createPanel() {
        var component = getComponent();
        infoTab = new TabInfo(component);
        infoTab.setText(RadicleBundle.message("Issue Overview"));
        infoTab.setSideComponent(createReturnToListSideComponent());

        final var uiDisposable = Disposer.newDisposable(issueTabController.getDisposer(),
                "RadicleIssuePanel");
        SingleHeightTabs tabs = new SingleHeightTabs(null, uiDisposable);
        tabs.addTab(infoTab);
        return tabs;
    }

    private JComponent getComponent() {
        var actionPanel = new JPanel();
        actionPanel.setOpaque(false);
        actionPanel.setLayout(new MigLayout(new LC().fillX().gridGap("0", "0").insets("0", "0", "0", "0")));
        Utils.addListPanel(actionPanel, assigneesSelect);
        Utils.addListPanel(actionPanel, stateSelect);
        Utils.addListPanel(actionPanel, tagSelect);
        final var splitter = new OnePixelSplitter(true, "Radicle.IssuePanel.action.Component", 0.6f);
        splitter.setFirstComponent(getIssueInfo());
        splitter.setSecondComponent(actionPanel);
        return splitter;
    }

    private JComponent getIssueInfo() {
        var detailsSection = new JPanel(new MigLayout(new LC()
                .insets("0", "5", "0", "0").gridGap("0", "0").fill().flowY()));
        detailsSection.setOpaque(false);
        detailsSection.setBorder(JBUI.Borders.empty(8));

        var issueTitle = getLabelPanel(RadicleBundle.message("title", "", Strings.nullToEmpty(issue.title)));
        detailsSection.add(issueTitle, new CC().gapBottom(String.valueOf(UI.scale(4))));

        var issueId = getLabelPanel(RadicleBundle.message("issueId", "", Strings.nullToEmpty(issue.id)));
        detailsSection.add(issueId, new CC().gapBottom(String.valueOf(UI.scale(4))));

        var issueAuthor = getLabelPanel(RadicleBundle.message("issueAuthor", "", Strings.nullToEmpty(issue.author.id)));
        detailsSection.add(issueAuthor, new CC().gapBottom(String.valueOf(UI.scale(4))));

        if (!issue.tags.isEmpty()) {
            var issueTag = getLabelPanel(RadicleBundle.message("issueTag", "", String.join(",", issue.tags)));
            detailsSection.add(issueTag, new CC().gapBottom(String.valueOf(UI.scale(4))));
        }

        if (!issue.assignees.isEmpty()) {
            var issueAssignees = getLabelPanel(RadicleBundle.message("issueAssignees", "", String.join(",", issue.assignees)));
            detailsSection.add(issueAssignees, new CC().gapBottom(String.valueOf(UI.scale(4))));
        }

        var issueState = getLabelPanel(RadicleBundle.message("issueState", "", Strings.nullToEmpty(issue.state.label)));
        detailsSection.add(issueState, new CC().gapBottom(String.valueOf(UI.scale(4))));

        var issueCreated = getLabelPanel(RadicleBundle.message("issueCreated", "", DATE_TIME_FORMATTER.format(issue.discussion.get(0).timestamp)));
        detailsSection.add(issueCreated, new CC().gapBottom(String.valueOf(UI.scale(4))));

        var borderPanel = new JPanel(new BorderLayout());
        borderPanel.add(detailsSection, BorderLayout.NORTH);
        return borderPanel;
    }

    private JComponent getLabelPanel(String txt) {
        var panel = new NonOpaquePanel();
        panel.setLayout(new MigLayout(new LC().fillX().gridGap("0", "0").insets("0", "0", "0", "0")));
        panel.add(new JLabel(txt), new CC().gapBottom(String.valueOf(UI.scale(10))));
        return panel;
    }

    protected JComponent createReturnToListSideComponent() {
        return ReturnToListComponent.INSTANCE.createReturnToListSideComponent(RadicleBundle.message("backToList"),
                () -> {
                    issueTabController.createPanel();
                    return Unit.INSTANCE;
                });
    }

    public AssigneesSelect getAssigneesSelect() {
        return assigneesSelect;
    }

    public StateSelect getStateSelect() {
        return stateSelect;
    }

    public TagSelect getTagSelect() {
        return tagSelect;
    }

    public class TagSelect extends Utils.LabeledListPanelHandle<TagSelect.Tag> {

        public record Tag(String tag) { }

        public static class TagRender extends Utils.SelectionListCellRenderer<TagSelect.Tag> {

            @Override
            public String getText(TagSelect.Tag value) {
                return value.tag;
            }

            @Override
            public String getPopupTitle() {
                return RadicleBundle.message("tag");
            }
        }

        @Override
        public String getSelectedValues() {
            return String.join(",", issue.tags);
        }

        @Override
        public boolean storeValues(List<Tag> tags) {
            var tagList = tags.stream().map(value -> value.tag).toList();
            var resp = api.addRemoveIssueTag(issue, tagList, issue.tags);
            var isSuccess = resp != null;
            if (isSuccess) {
                issueModel.setValue(issue);
            }
            return isSuccess;
        }

        @Override
        public CompletableFuture<List<Tag>> showEditPopup(JComponent parent) {
            var addField = new JBTextField();
            var res = new CompletableFuture<List<Tag>>();
            jbPopup = Utils.PopupBuilder.createPopup(this.getData(), new TagRender(), false, addField, res);
            jbPopup.showUnderneathOf(parent);
            listener = Utils.PopupBuilder.myListener;
            return res.thenApply(data -> {
                if (Strings.isNullOrEmpty(addField.getText())) {
                    return data;
                }
                var myList = new ArrayList<>(data);
                myList.add(new Tag(addField.getText()));
                return myList;
            });
        }

        @Override
        public Utils.SelectionListCellRenderer<Tag> getRender() {
            return new TagRender();
        }

        @Override
        public CompletableFuture<List<Utils.SelectableWrapper<Tag>>> getData() {
            return CompletableFuture.supplyAsync(() -> {
                var tagFuture = new ArrayList<Utils.SelectableWrapper<TagSelect.Tag>>();
                for (String tag : issue.tags) {
                    var selectableWrapper = new Utils.SelectableWrapper<>(new TagSelect.Tag(tag), true);
                    tagFuture.add(selectableWrapper);
                }
                return tagFuture;
            });
        }

        @Override
        public String getLabel() {
            return RadicleBundle.message("tag");
        }

        @Override
        public boolean isSingleSelection() {
            return false;
        }
    }

    public class StateSelect extends Utils.LabeledListPanelHandle<StateSelect.State> {

        public record State(String status, String label) { }

        public static class StateRender extends Utils.SelectionListCellRenderer<StateSelect.State> {

            @Override
            public String getText(StateSelect.State value) {
                return value.label;
            }

            @Override
            public String getPopupTitle() {
                return RadicleBundle.message("state");
            }
        }

        @Override
        public String getSelectedValues() {
            return issue.state.label;
        }

        @Override
        public boolean storeValues(List<StateSelect.State> data) {
            var selectedState = data.get(0).status;
            if (selectedState.equals(issue.state.status)) {
                return true;
            }
            var resp = api.changeIssueState(issue, selectedState);
            var isSuccess = resp != null;
            if (isSuccess) {
                issueModel.setValue(issue);
            }
            return isSuccess;
        }

        @Override
        public Utils.SelectionListCellRenderer<State> getRender() {
            return new StateSelect.StateRender();
        }

        @Override
        public CompletableFuture<List<Utils.SelectableWrapper<State>>> getData() {
            return CompletableFuture.supplyAsync(() -> {
                var allStates = Arrays.stream(RadIssue.State.values()).map(e -> new State(e.status, e.label)).toList();
                var stateList = new ArrayList<Utils.SelectableWrapper<StateSelect.State>>();
                for (State state : allStates) {
                    var isSelected = issue.state.status.equals(state.status);
                    var selectableWrapper = new Utils.SelectableWrapper<>(state, isSelected);
                    stateList.add(selectableWrapper);
                }
                return stateList;
            });
        }

        @Override
        public String getLabel() {
            return RadicleBundle.message("state");
        }

        @Override
        public boolean isSingleSelection() {
            return true;
        }
    }

    public class AssigneesSelect extends Utils.LabeledListPanelHandle<AssigneesSelect.Assignee> {

        public record Assignee(String name) { }

        public static class AssigneeRender extends Utils.SelectionListCellRenderer<AssigneesSelect.Assignee> {

            @Override
            public String getText(AssigneesSelect.Assignee value) {
                return value.name();
            }

            @Override
            public String getPopupTitle() {
                return RadicleBundle.message("assignees");
            }
        }

        @Override
        public String  getSelectedValues() {
            return String.join(",", issue.assignees);
        }

        @Override
        public boolean storeValues(List<AssigneesSelect.Assignee> data) {
            var removeAssignees = issue.assignees.stream().map(this::getAuthorId).collect(Collectors.toList());
            var addAssignees = data.stream().map(assignee -> getAuthorId(assignee.name())).toList();
            var resp = api.addRemoveIssueAssignees(issue, addAssignees, removeAssignees);
            var isSuccess = resp != null;
            if (isSuccess) {
                issueModel.setValue(issue);
            }
            return isSuccess;
        }

        @Override
        public Utils.SelectionListCellRenderer<Assignee> getRender() {
            return new AssigneesSelect.AssigneeRender();
        }

        @Override
        public CompletableFuture<List<Utils.SelectableWrapper<Assignee>>> getData() {
            return CompletableFuture.supplyAsync(() -> {
                var projectInfo = api.fetchRadProject(issue.projectId);
                var assignees = new ArrayList<Utils.SelectableWrapper<AssigneesSelect.Assignee>>();
                for (var delegate : projectInfo.delegates) {
                    var assignee = new AssigneesSelect.Assignee(delegate);
                    var isSelected = issue.assignees.contains(delegate);
                    var selectableWrapper = new Utils.SelectableWrapper<>(assignee, isSelected);
                    assignees.add(selectableWrapper);
                }
                return assignees;
            });
        }

        @Override
        public String getLabel() {
            return RadicleBundle.message("assignees");
        }

        @Override
        public boolean isSingleSelection() {
            return false;
        }

        public String getAuthorId(String authorDid) {
            var parts = authorDid.split(AUTHOR_SPLIT_CHARACTER);
            if (parts.length > 2) {
                return parts[2];
            }
            return "";
        }
    }
}
