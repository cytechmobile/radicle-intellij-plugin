package network.radicle.jetbrains.radiclejetbrainsplugin.patches;

import com.google.common.base.Strings;
import com.intellij.collaboration.ui.codereview.list.search.ReviewListQuickFilter;
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchHistoryModel;
import com.intellij.openapi.project.Project;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.MutableStateFlow;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadAuthor;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.SearchViewModelBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class PatchSearchPanelViewModel extends SearchViewModelBase<PatchListSearchValue, PatchSearchPanelViewModel.PatchListQuickFilter, RadPatch> {


    public PatchSearchPanelViewModel(@NotNull CoroutineScope scope, @NotNull ReviewListSearchHistoryModel<PatchListSearchValue> historyModel, Project project) {
        super(scope, historyModel, new PatchListSearchValue(), new PatchListSearchValue(), project);
    }

    public MutableStateFlow<String> authorFilterState() {
        return partialState(getSearchState(), PatchListSearchValue::getAuthor,
                (Function2<PatchListSearchValue, Object, PatchListSearchValue>) (patchListSearchValue, authorName) -> {
                    var copyPatchSearchValue = new PatchListSearchValue(patchListSearchValue);
                    copyPatchSearchValue.author = (String) authorName;
                    return copyPatchSearchValue;
                });
    }

    public MutableStateFlow<String> projectFilterState() {
        return partialState(getSearchState(), PatchListSearchValue::getProject,
                (Function2<PatchListSearchValue, Object, PatchListSearchValue>) (patchListSearchValue, projectName) -> {
                    var copyPatchSearchValue = new PatchListSearchValue(patchListSearchValue);
                    copyPatchSearchValue.project = (String) projectName;
                    return copyPatchSearchValue;
                });
    }

    public MutableStateFlow<String> stateFilter() {
        return partialState(getSearchState(), PatchListSearchValue::getState,
                (Function2<PatchListSearchValue, Object, PatchListSearchValue>) (patchListSearchValue, state) -> {
                    var copyPatchSearchValue = new PatchListSearchValue(patchListSearchValue);
                    copyPatchSearchValue.state = (String) state;
                    return copyPatchSearchValue;
                });
    }

    public MutableStateFlow<String> labelFilter() {
        return partialState(getSearchState(), PatchListSearchValue::getLabel,
                (Function2<PatchListSearchValue, Object, PatchListSearchValue>) (patchListSearchValue, label) -> {
                    var copyPatchSearchValue = new PatchListSearchValue(patchListSearchValue);
                    copyPatchSearchValue.label = (String) label;
                    return copyPatchSearchValue;
                });
    }

    public CompletableFuture<List<String>> getStateLabels() {
        return CompletableFuture.supplyAsync(() ->
                Arrays.stream(RadPatch.State.values()).map(e -> e.label).collect(Collectors.toList()));
    }

    @Override
    protected String getSelectedProjectFilter() {
        return this.getSearchState().getValue().project;
    }

    @Override
    protected List<String> getLabels(RadPatch patch) {
        return patch.labels;
    }

    @Override
    protected List<RadPatch> filterListByProject() {
        var selectedProject = getSelectedProjectFilter();
        if (Strings.isNullOrEmpty(selectedProject)) {
            return myList;
        }
        return myList.stream().filter(patch -> patch.repo.getRoot().getName().equals(selectedProject))
                .collect(Collectors.toList());
    }

    @Override
    protected RadAuthor getAuthor(RadPatch issue) {
        return issue.author;
    }

    @NotNull
    @Override
    protected PatchListSearchValue withQuery(@NotNull PatchListSearchValue patchListSearchValue, @Nullable String searchStr) {
        var copyPatchSearchValue = new PatchListSearchValue(patchListSearchValue);
        copyPatchSearchValue.searchQuery = searchStr;
        return copyPatchSearchValue;
    }

    @Override
    public List<PatchListQuickFilter> getQuickFilters() {
        var openFilter = new PatchListQuickFilter();
        openFilter.patchListSearchValue.state = RadPatch.State.OPEN.label;

        var draftFilter = new PatchListQuickFilter();
        draftFilter.patchListSearchValue.state = RadPatch.State.DRAFT.label;

        var archivedFilter = new PatchListQuickFilter();
        archivedFilter.patchListSearchValue.state = RadPatch.State.ARCHIVED.label;

        return List.of(openFilter, draftFilter, archivedFilter);
    }

    public static class PatchListQuickFilter implements ReviewListQuickFilter<PatchListSearchValue> {

        private final PatchListSearchValue patchListSearchValue;

        public PatchListQuickFilter() {
            patchListSearchValue = new PatchListSearchValue();
        }

        @NotNull
        @Override
        public PatchListSearchValue getFilter() {
            return patchListSearchValue;
        }
    }

}
