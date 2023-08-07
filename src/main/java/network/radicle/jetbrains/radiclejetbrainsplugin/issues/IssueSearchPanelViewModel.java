package network.radicle.jetbrains.radiclejetbrainsplugin.issues;

import com.google.common.base.Strings;
import com.intellij.collaboration.ui.codereview.list.search.ReviewListQuickFilter;
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchHistoryModel;
import com.intellij.openapi.project.Project;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.MutableStateFlow;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadAuthor;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadIssue;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.SearchViewModelBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class IssueSearchPanelViewModel extends SearchViewModelBase<IssueListSearchValue, IssueSearchPanelViewModel.IssueListQuickFilter, RadIssue> {
    public IssueSearchPanelViewModel(@NotNull CoroutineScope scope,
                                     @NotNull ReviewListSearchHistoryModel<IssueListSearchValue> historyModel,
                                     Project project) {
        super(scope, historyModel, new IssueListSearchValue(), new IssueListQuickFilter(), project);
    }

    public MutableStateFlow<String> authorFilterState() {
        return partialState(getSearchState(), IssueListSearchValue::getAuthor,
                (Function2<IssueListSearchValue, Object, IssueListSearchValue>) (issueListSearchValue, authorName) -> {
                    var copyIssueSearchValue = new IssueListSearchValue(issueListSearchValue);
                    copyIssueSearchValue.author = (String) authorName;
                    return copyIssueSearchValue;
                });
    }

    public MutableStateFlow<String> projectFilterState() {
        return partialState(getSearchState(), IssueListSearchValue::getProject,
                (Function2<IssueListSearchValue, Object, IssueListSearchValue>) (issueListSearchValue, projectName) -> {
                    var copyIssueSearchValue = new IssueListSearchValue(issueListSearchValue);
                    copyIssueSearchValue.project = (String) projectName;
                    return copyIssueSearchValue;
                });
    }

    public MutableStateFlow<String> stateFilter() {
        return partialState(getSearchState(), IssueListSearchValue::getState,
                (Function2<IssueListSearchValue, Object, IssueListSearchValue>) (issueListSearchValue, state) -> {
                    var copyIssueSearchValue = new IssueListSearchValue(issueListSearchValue);
                    copyIssueSearchValue.state = (String) state;
                    return copyIssueSearchValue;
                });
    }

    public MutableStateFlow<String> assigneeFilter() {
        return partialState(getSearchState(), IssueListSearchValue::getAssignee,
                (Function2<IssueListSearchValue, Object, IssueListSearchValue>) (issueListSearchValue, assignee) -> {
                    var copyIssueSearchValue = new IssueListSearchValue(issueListSearchValue);
                    copyIssueSearchValue.assignee = (String) assignee;
                    return copyIssueSearchValue;
                });
    }

    public MutableStateFlow<String> tagFilter() {
        return partialState(getSearchState(), IssueListSearchValue::getTag,
                (Function2<IssueListSearchValue, Object, IssueListSearchValue>) (issueListSearchValue, tag) -> {
                    var copyIssueSearchValue = new IssueListSearchValue(issueListSearchValue);
                    copyIssueSearchValue.tag = (String) tag;
                    return copyIssueSearchValue;
                });
    }

    public CompletableFuture<List<String>> getAssignees() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> assigness = new ArrayList<>();
            var filteredList = filterListByProject();
            for (var issue : filteredList) {
                for (var assignee : issue.assignees) {
                    if (!assigness.contains(assignee)) {
                        assigness.add(assignee);
                    }
                }
            }
            return assigness;
        });
    }

    @Override
    protected String getSelectedProjectFilter() {
        return this.getSearchState().getValue().project;
    }

    @Override
    protected List<String> getItemTags(RadIssue issue) {
        return issue.tags;
    }

    @Override
    protected List<RadIssue> filterListByProject() {
        var selectedProject = getSelectedProjectFilter();
        if (Strings.isNullOrEmpty(selectedProject)) {
            return myList;
        }
        return myList.stream().filter(issue -> issue.repo.getRoot().getName().equals(selectedProject))
                .collect(Collectors.toList());
    }

    @Override
    protected RadAuthor getAuthor(RadIssue item) {
        return item.author;
    }

    @NotNull
    @Override
    public List<IssueListQuickFilter> getQuickFilters() {
        var openFilter = new IssueSearchPanelViewModel.IssueListQuickFilter();
        openFilter.issueListSearchValue.state = RadIssue.State.OPEN.label;

        var closedFilter = new IssueSearchPanelViewModel.IssueListQuickFilter();
        closedFilter.issueListSearchValue.state = RadIssue.State.CLOSED.label;

        return List.of(openFilter, closedFilter);
    }

    @NotNull
    @Override
    protected IssueListSearchValue withQuery(@NotNull IssueListSearchValue issueListSearchValue, @Nullable String searchStr) {
        var copyIssueSearchValue = new IssueListSearchValue(issueListSearchValue);
        copyIssueSearchValue.searchQuery = searchStr;
        return copyIssueSearchValue;
    }

    public static class IssueListQuickFilter implements ReviewListQuickFilter<IssueListSearchValue> {
        private final IssueListSearchValue issueListSearchValue;
        public IssueListQuickFilter() {
            issueListSearchValue = new IssueListSearchValue();
            // Set OPEN as default filter
            issueListSearchValue.state = RadIssue.State.OPEN.label;
        }

        @NotNull
        @Override
        public IssueListSearchValue getFilter() {
            return issueListSearchValue;
        }
    }
}
