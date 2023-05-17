package network.radicle.jetbrains.radiclejetbrainsplugin.patches;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline.editor.PatchVirtualFile;
import network.radicle.jetbrains.radiclejetbrainsplugin.providers.ProjectApi;

import java.awt.BorderLayout;
import java.util.Arrays;

public class PatchTabController {
    private Project project;
    private Content tab;
    private PatchListPanel patchListPanel;
    private PatchProposalPanel patchProposalPanel;
    private ProjectApi myApi;

    public PatchTabController(Content tab, Project project, ProjectApi myApi) {
        this.tab = tab;
        this.project = project;
        this.myApi = myApi;
    }

    public void createPatchesPanel() {
        tab.setDisplayName(RadicleBundle.message("patchTabName"));
        var mainPanel = tab.getComponent();
        patchListPanel = new PatchListPanel(this, project, myApi);
        var createdPanel = patchListPanel.create();
        mainPanel.setLayout(new BorderLayout(5, 10));
        mainPanel.removeAll();
        mainPanel.add(createdPanel, BorderLayout.CENTER);
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void createPatchProposalPanel(RadPatch patch) {
        tab.setDisplayName("Patch Proposal from: " + patch.author);
        patchProposalPanel = new PatchProposalPanel();
        var panel = patchProposalPanel.createViewPatchProposalPanel(this, patch, project);
        var mainPanel = tab.getComponent();
        mainPanel.setLayout(new BorderLayout());
        mainPanel.removeAll();
        mainPanel.add(panel, BorderLayout.CENTER);
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void openPatchTimelineOnEditor(RadPatch patch) {
        var editorManager = FileEditorManager.getInstance(project);
        var file = new PatchVirtualFile(patch);
        var editor = Arrays.stream(editorManager.getAllEditors()).filter(ed ->
                ed.getFile() instanceof PatchVirtualFile &&
                        ((PatchVirtualFile) ed.getFile()).getPatch().id.equals(patch.id)).findFirst();
        if (editor.isEmpty()) {
            editorManager.openFile(file, true);
        }
    }

    public Disposable getDisposer() {
        return tab.getDisposer();
    }

    public PatchListPanel getPatchListPanel() {
        return patchListPanel;
    }
}
