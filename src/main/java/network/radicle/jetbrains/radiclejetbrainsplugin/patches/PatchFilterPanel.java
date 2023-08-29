package network.radicle.jetbrains.radiclejetbrainsplugin.patches;

import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil;
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil.PopupItemPresentation;
import com.intellij.collaboration.ui.codereview.list.search.DropDownComponentFactory;
import com.intellij.collaboration.ui.codereview.list.search.PopupConfig;
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchPanelFactory;
import kotlinx.coroutines.CoroutineScope;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.PopupBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PatchFilterPanel extends ReviewListSearchPanelFactory<PatchListSearchValue,
        PatchSearchPanelViewModel.PatchListQuickFilter, PatchSearchPanelViewModel> {
    private final PatchSearchPanelViewModel viewModel;

    public PatchFilterPanel(@NotNull PatchSearchPanelViewModel patchSearchPanelViewModel) {
        super(patchSearchPanelViewModel);
        this.viewModel = patchSearchPanelViewModel;
    }

    @NotNull
    @Override
    protected List<JComponent> createFilters(@NotNull CoroutineScope coroutineScope) {
        var stateFilter = new DropDownComponentFactory<>(this.viewModel.stateFilter()).create(coroutineScope, RadicleBundle.message("state"), o -> o,
                (relativePoint, continuation) -> ChooserPopupUtil.INSTANCE.showAsyncChooserPopup(relativePoint,
                        continuation1 -> Arrays.stream(RadPatch.State.values()).map(e -> e.label).collect(Collectors.toList()),
                        state -> new PopupItemPresentation.Simple((String) state, null, null),
                        PopupConfig.Companion.getDEFAULT(), continuation));

        var projectFilter = new DropDownComponentFactory<>(this.viewModel.projectFilterState()).create(coroutineScope, RadicleBundle.message("project"), o -> o,
                (relativePoint, continuation) -> {
                    var popUpBuilder = new PopupBuilder();
                    var popUp = popUpBuilder.createPopup(this.viewModel.getProjectNames(), this.viewModel.getCountDown());
                    return ChooserPopupUtil.INSTANCE.showAndAwaitListSubmission(popUp, relativePoint, continuation);
                });

        var authorFilter = new DropDownComponentFactory<>(this.viewModel.authorFilterState()).create(coroutineScope, RadicleBundle.message("author"), o -> o,
                (relativePoint, continuation) -> {
                    var popUpBuilder = new PopupBuilder();
                    var popUp = popUpBuilder.createPopup(this.viewModel.getAuthors(), this.viewModel.getCountDown());
                    return ChooserPopupUtil.INSTANCE.showAndAwaitListSubmission(popUp, relativePoint, continuation);
                });

        var labelFilter = new DropDownComponentFactory<>(this.viewModel.labelFilter()).create(coroutineScope, RadicleBundle.message("label"), o -> o,
                (relativePoint, continuation) -> {
                    var popUpBuilder = new PopupBuilder();
                    var popUp = popUpBuilder.createPopup(this.viewModel.getLabels(), this.viewModel.getCountDown());
                    return ChooserPopupUtil.INSTANCE.showAndAwaitListSubmission(popUp, relativePoint, continuation);
                });

        return List.of(stateFilter, projectFilter, authorFilter, labelFilter);
    }

    @NotNull
    @Override
    protected String getShortText(@NotNull PatchListSearchValue patchListSearchValue) {
        return "";
    }

    @NotNull
    @Override
    protected String getQuickFilterTitle(@NotNull PatchSearchPanelViewModel.PatchListQuickFilter patchListQuickFilter) {
        return Objects.requireNonNullElse(patchListQuickFilter.getFilter().state, "");
    }
}
