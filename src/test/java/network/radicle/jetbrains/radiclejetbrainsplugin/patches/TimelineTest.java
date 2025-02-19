package network.radicle.jetbrains.radiclejetbrainsplugin.patches;

import com.fasterxml.jackson.core.type.TypeReference;
import com.intellij.collaboration.ui.JPanelWithBackground;
import com.intellij.collaboration.ui.SingleValueModel;
import com.intellij.collaboration.ui.codereview.BaseHtmlEditorPane;
import com.intellij.collaboration.ui.codereview.timeline.thread.TimelineThreadCommentsPanel;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffContext;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.execution.CommandLineUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.ClipboardSynchronizer;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.EditorFactoryImpl;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.ui.InlineIconButton;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.components.BorderLayoutPanel;
import git4idea.GitCommit;
import git4idea.GitUtil;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import network.radicle.jetbrains.radiclejetbrainsplugin.AbstractIT;
import network.radicle.jetbrains.radiclejetbrainsplugin.GitExecutor;
import network.radicle.jetbrains.radiclejetbrainsplugin.GitTestUtil;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadStub;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.ReviewSubmitAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.Embed;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.Emoji;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadAuthor;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadDiscussion;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadProject;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.Reaction;
import network.radicle.jetbrains.radiclejetbrainsplugin.patches.review.PatchDiffEditorGutterIconFactory;
import network.radicle.jetbrains.radiclejetbrainsplugin.patches.timeline.editor.PatchEditorProvider;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleCliService;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.DragAndDropField;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.PatchDiffWindow;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.RadicleToolWindow;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.SelectionListCellRenderer;
import network.radicle.jetbrains.radiclejetbrainsplugin.toolwindow.Utils;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class TimelineTest extends AbstractIT {
    private static final Logger logger = Logger.getInstance(TimelineTest.class);
    public static final String RAD_PROJECT_ID = "rad:123";
    private static final String DISCUSSION_ID = UUID.randomUUID().toString();
    private static final String OUTDATED_DISCUSSION_ID = UUID.randomUUID().toString();
    private static final String REVIEW_SUMMARY = "Accepted";

    private FileEditor myEditor;
    public static RadPatch patch;
    private String dummyComment = "Hello";
    private String replyComment = "This is my reply";
    private PatchEditorProvider patchEditorProvider;
    private VirtualFile editorFile;
    private PatchTabController patchTabController;
    private Embed txtEmbed;
    private Embed imgEmbed;
    private String firstComment;
    private String secondComment;
    private String currentRevision = null;
    List<EditorImpl> editors = new ArrayList<>();

    @Rule
    public TestName testName = new TestName();

    @Before
    public void beforeTest() throws InterruptedException {
        patch = createPatch();
        currentRevision = firstRepo.getCurrentRevision();
        replaceCliService(currentRevision, true);

        radicleProjectSettingsHandler.saveRadHome(AbstractIT.RAD_HOME);
        RadicleToolWindow radicleToolWindow = new RadicleToolWindow();
        var mockToolWindow = new PatchListPanelTest.MockToolWindow(super.getProject());
        radicleToolWindow.createToolWindowContent(super.getProject(), mockToolWindow);
        radicleToolWindow.toolWindowManagerListener.toolWindowShown(mockToolWindow);
        patchTabController = radicleToolWindow.patchTabController;
        if (testName.getMethodName().equals("testReactions")) {
            // Don't recreate PatchProposalPanel after success request for this test
            patchTabController.createInternalPatchProposalPanel(new SingleValueModel<>(patch), new JPanel());
        } else {
            patchTabController.createPatchProposalPanel(patch);
        }
        var editorManager = FileEditorManager.getInstance(getProject());
        var allEditors = editorManager.getAllEditors();
        assertThat(allEditors.length).isEqualTo(1);
        var editor = allEditors[0];
        editorFile = editor.getFile();
        var providerManager = FileEditorProviderManager.getInstance();
        var providers = providerManager.getProviders(getProject(), editorFile);
        assertThat(providers.length).isEqualTo(1);
        patchEditorProvider = (PatchEditorProvider) providers[0];
        // Open createEditor
        myEditor = patchEditorProvider.createEditor(getProject(), editorFile);

        /* Wait to load the patches */
        executeUiTasks();
        Thread.sleep(200);
        executeUiTasks();
    }

    @After
    public final void afterTest() throws Exception {
        if (editors != null && !editors.isEmpty()) {
            for (var e : editors) {
                if (e.isDisposed()) {
                    continue;
                }
                EditorFactory.getInstance().releaseEditor(e);
            }
        }
    }

    @Test
    public void testReviewCommentsExists() throws Exception {
        var comment = "This is a comment";
        var outDatedComment = "This is a outdated comment";
        var firstCommit = commitHistory.getFirst();
        var firstChange = firstCommit.getChanges().stream().findFirst().orElseThrow();
        var fileName = findPath(firstChange.getVirtualFile());
        var location = new RadDiscussion.Location(fileName, "range", firstChange.getAfterRevision().getRevisionNumber().asString(), 0, 0);
        patch.getRevisionList().get(1).discussion().comments.put(DISCUSSION_ID, createDiscussionWithLocation(comment, List.of(), location));
        patch.getRevisionList().get(0).discussion().comments.put(OUTDATED_DISCUSSION_ID, createDiscussionWithLocation(outDatedComment, List.of(), location));
        var patchDiffWindow = initializeDiffWindow(firstCommit);
        var editor = patchDiffWindow.getEditor();
        editors.add(editor);
        boolean findOutDatedComment = false;
        boolean findNonOutDatedComment = false;
        for (int i = 0; i < 20; i++) {
            if (editor.getContentComponent().getComponents().length > 0) {
                break;
            }
            logger.warn("Waiting for editor components");
            executeUiTasks();
            Thread.sleep(500);
        }
        for (var comp : editor.getContentComponent().getComponents()) {
            var timelineThreadPanel = UIUtil.findComponentOfType((JComponent) comp, TimelineThreadCommentsPanel.class);
            var jPanelWithBackground = UIUtil.findComponentOfType(timelineThreadPanel, JPanelWithBackground.class);
            var scrollablePanel = (JPanel) ((BorderLayoutPanel) jPanelWithBackground.getComponents()[0]).getComponents()[0];
            var authorLabel = UIUtil.findComponentOfType((JPanel) scrollablePanel.getComponents()[0], JLabel.class);
            var commentLabel = UIUtil.findComponentOfType((JPanel) scrollablePanel.getComponents()[1], JEditorPane.class);
            assertThat(authorLabel.getText()).contains(RadStub.SELF_ALIAS);
            if (authorLabel.getText().contains("OUTDATED")) {
                findOutDatedComment = true;
                assertThat(commentLabel.getText()).contains(outDatedComment);
            } else {
                findNonOutDatedComment = true;
                assertThat(commentLabel.getText()).contains(comment);
            }
        }
        assertThat(findOutDatedComment).isTrue();
        assertThat(findNonOutDatedComment).isTrue();
        EditorFactory.getInstance().releaseEditor(editor);
        var fileToChange = new File(firstRepo.getRoot().getPath() + "/" + fileName);
        GitTestUtil.writeToFile(fileToChange, "Welcome");
        var commitNumber =  GitExecutor.addCommit("my third message");
        var commit = getInBackground(() -> GitTestUtil.findCommit(firstRepo, commitNumber));
        assertThat(commit).isNotNull();
        patchDiffWindow = initializeDiffWindow(commit);
        editor = patchDiffWindow.getEditor();
        editors.add(editor);

        for (var comp : editor.getContentComponent().getComponents()) {
            var timelineThreadPanel = UIUtil.findComponentOfType((JComponent) comp, TimelineThreadCommentsPanel.class);
            var jPanelWithBackground = UIUtil.findComponentOfType(timelineThreadPanel, JPanelWithBackground.class);
            var scrollablePanel = (JPanel) ((BorderLayoutPanel) jPanelWithBackground.getComponents()[0]).getComponents()[0];
            var commentLabel = UIUtil.findComponentOfType((JPanel) scrollablePanel.getComponents()[1], JEditorPane.class);
            assertThat(commentLabel.getText()).doesNotContain(outDatedComment);
            assertThat(commentLabel.getText()).contains(comment);
        }
    }

    @Test
    public void testDeleteReviewsComments() throws Exception {
        //Save the password in order to bypass the identity dialog
        radicleProjectSettingsHandler.savePassphrase("testPublicKey", "test");
        var comment = "This is a comment";
        var firstCommit = commitHistory.getFirst();
        var firstChange = firstCommit.getChanges().stream().findFirst().orElseThrow();
        var fileName = findPath(firstChange.getVirtualFile());
        var location = new RadDiscussion.Location(fileName, "range", firstChange.getAfterRevision().getRevisionNumber().asString(), 0, 0);
        var discussion = createDiscussionWithLocation(comment, List.of(), location);
        patch.getRevisionList().get(1).discussion().comments.put(DISCUSSION_ID, discussion);
        var patchDiffWindow = initializeDiffWindow(firstCommit);
        var editor = patchDiffWindow.getEditor();
        editors.add(editor);
        var contentComponent = (JComponent) editor.getContentComponent().getComponents()[0];

        var timelineThreadPanel = UIUtil.findComponentOfType(contentComponent, TimelineThreadCommentsPanel.class);
        var jPanelWithBackground = UIUtil.findComponentOfType(timelineThreadPanel, JPanelWithBackground.class);
        var scrollablePanel = (JPanel) ((BorderLayoutPanel) jPanelWithBackground.getComponents()[0]).getComponents()[0];
        var myPanel = (JPanel) scrollablePanel.getComponents()[0];
        /* if (true) {
            logger.warn("comment deletion is disabled");
            return;
        } */
        var actionPanel = (JPanel) myPanel.getComponents()[1];
        var deleteIcon = (InlineIconButton) actionPanel.getComponents()[1];

        deleteIcon.setActionListener(e -> patchDiffWindow.getPatchReviewThreadsController()
                .getPatchDiffEditorComponentsFactory().getPatchReviewThreadComponentFactory().deleteComment(discussion));
        deleteIcon.getActionListener().actionPerformed(new ActionEvent(deleteIcon, 0, ""));
        executeUiTasks();
    }

    @Test
    public void testAddReview() throws Exception {
        var comment = "This is a comment";
        var firstCommit = commitHistory.getFirst();
        var firstChange = firstCommit.getChanges().stream().findFirst().orElseThrow();
        var fileName = firstChange.getVirtualFile().getPath();
        var location = new RadDiscussion.Location(fileName, "range", firstChange.getAfterRevision().getRevisionNumber().asString(), 0, 0);
        patch.getRevisionList().get(1).getDiscussions().add(createDiscussionWithLocation(comment, List.of(), location));
        var patchDiffWindow = initializeDiffWindow(firstCommit);
        var editor = patchDiffWindow.getEditor();
        editors.add(editor);
        var reviewAction = new ReviewSubmitAction(patch);
        reviewAction.setUpPopup(new JPanel());

        var componentContainer = reviewAction.getContainer();
        var editorTextField = UIUtil.findComponentOfType(componentContainer.getComponent(), EditorTextField.class);
        var approveButton = UIUtil.findComponentOfType(componentContainer.getComponent(), JButton.class);

        editorTextField.setText(REVIEW_SUMMARY);
        approveButton.doClick();
        executeUiTasks();
        var command = radStub.commandsStr.poll(5, TimeUnit.SECONDS);
        assertThat(command).contains(RadPatch.Review.Verdict.ACCEPT.getValue());
        assertThat(command).contains(CommandLineUtil.posixQuote(REVIEW_SUMMARY));
        assertThat(command).contains("review");
    }

    @Test
    public void testReviewCommentsReply() throws Exception {
        var comment = "This is a comment";
        var firstCommit = commitHistory.getFirst();
        var firstChange = firstCommit.getChanges().stream().findFirst().orElseThrow();
        var fileName = findPath(firstChange.getVirtualFile());
        var location = new RadDiscussion.Location(fileName, "range", firstChange.getAfterRevision().getRevisionNumber().asString(), 0, 0);
        patch.getRevisionList().get(1).discussion().comments.put(DISCUSSION_ID,
                createDiscussionWithLocation(comment, List.of(), location));
        var patchDiffWindow = initializeDiffWindow(firstCommit);
        var editor = patchDiffWindow.getEditor();
        editors.add(editor);
        var contentComponent = (JComponent) editor.getContentComponent().getComponents()[0];

        var replyButton = UIUtil.findComponentsOfType(contentComponent, LinkLabel.class);
        assertThat(replyButton.size()).isEqualTo(1);
        replyButton.getFirst().doClick();

        var ef = UIUtil.findComponentOfType(contentComponent, DragAndDropField.class);
        assertThat(ef).isNotNull();
        markAsShowing(ef.getParent(), ef);
        assertThat(ef.getText()).isEmpty();
        executeUiTasks();
        ef.setText(replyComment);
        var prBtns = UIUtil.findComponentsOfType(contentComponent, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        var prBtn = prBtns.get(1);
        prBtn.doClick();
        executeUiTasks();
        var cmds = new ArrayList<RadicleNativeStub.Capture>();
        nativeStub.getCommands().drainTo(cmds);
        if (cmds.stream().filter(c -> c.method().equals("createPatchComment") && c.input().contains(replyComment)).findFirst().isEmpty()) {
            executeUiTasks();
            var cmd = nativeStub.getCommands().poll(5, TimeUnit.SECONDS);
            if (cmd != null) {
                cmds.add(cmd);
            }
        }
        assertThat(cmds).anyMatch(c -> c.method().equals("createPatchComment") &&
                c.input().contains(replyComment) && c.input().contains(DISCUSSION_ID));
    }

    @Test
    public void testAddReviewComments() throws Exception {
        var firstCommit = commitHistory.getFirst();
        var firstChange = firstCommit.getChanges().stream().findFirst().orElseThrow();
        var fileName = findPath(firstChange.getVirtualFile());
        var patchDiffWindow = initializeDiffWindow(firstCommit);
        var gutterIconFactory = patchDiffWindow.getPatchDiffEditorGutterIconFactory();
        var commentRenderer = (PatchDiffEditorGutterIconFactory.CommentIconRenderer) gutterIconFactory.createCommentRenderer(0);

        final var ae = new AnActionEvent(SimpleDataContext.getProjectContext(myProject), new Presentation(""), "", ActionUiKind.NONE, null, 0,
                ActionManager.getInstance());
        commentRenderer.createComment().actionPerformed(ae);
        var editor = patchDiffWindow.getEditor();
        editors.add(editor);
        var contentComponent = (JComponent) editor.getContentComponent().getComponents()[0];
        var ef = UIUtil.findComponentOfType(contentComponent, DragAndDropField.class);
        assertThat(ef).isNotNull();
        markAsShowing(ef.getParent(), ef);
        executeUiTasks();
        ef.setText(dummyComment);
        var prBtns = UIUtil.findComponentsOfType(contentComponent, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        var prBtn = prBtns.getFirst();
        prBtn.doClick();
        //Comment
        executeUiTasks();
        var cmds = new ArrayList<RadicleNativeStub.Capture>();
        nativeStub.getCommands().drainTo(cmds);
        if (cmds.stream().filter(c -> c.method().equals("createPatchComment") && c.input().contains(dummyComment) &&
                                      c.input().contains(patch.getRevisionList().get(1).id())).findFirst().isEmpty()) {
            executeUiTasks();
            var cmd = nativeStub.getCommands().poll(5, TimeUnit.SECONDS);
            if (cmd != null) {
                cmds.add(cmd);
            }
        }
        final var matchedCmd = cmds.stream().filter(c -> c.method().equals("createPatchComment") && c.input().contains(dummyComment) &&
            c.input().contains(patch.getRevisionList().get(1).id())).findFirst().orElseThrow();
        Map<String, Object> params = RadicleCliService.MAPPER.readValue(matchedCmd.input(), new TypeReference<>() { });
        assertThat(params.get("comment")).isEqualTo(dummyComment);
        // TODO: jrad: locations are not available from CLI
        var locationObj = (Map<String, Object>) params.get("location");
        assertThat((String) locationObj.get("path")).isEqualTo(fileName);
        var newObj = (Map<String, Object>) locationObj.get("new");
        assertThat((String) newObj.get("type")).isEqualTo("lines");
        var range = (Map<String, Integer>) newObj.get("range");
        assertThat(range.get("start")).isEqualTo(0);
        assertThat(range.get("end")).isEqualTo(0);
    }

    @Test
    public void testEditReviewComments() throws Exception {
        var comment = "This is a comment";
        var firstCommit = commitHistory.getFirst();
        var firstChange = firstCommit.getChanges().stream().findFirst().orElseThrow();
        var fileName = findPath(firstChange.getVirtualFile());
        var location = new RadDiscussion.Location(fileName, "range", firstChange.getAfterRevision().getRevisionNumber().asString(), 0, 0);
        patch.getRevisionList().get(1).discussion().comments.put(DISCUSSION_ID, createDiscussionWithLocation(comment, List.of(), location));
        var patchDiffWindow = initializeDiffWindow(firstCommit);
        var editor = patchDiffWindow.getEditor();
        editors.add(editor);
        var contentComponent = (JComponent) editor.getContentComponent().getComponents()[0];

        var editBtn = UIUtil.findComponentOfType(contentComponent, InlineIconButton.class);
        //send event that we clicked edit
        editBtn.getActionListener().actionPerformed(new ActionEvent(editBtn, 0, ""));
        var ef = UIUtil.findComponentOfType(contentComponent, DragAndDropField.class);
        assertThat(ef).isNotNull();
        markAsShowing(ef.getParent(), ef);
        executeUiTasks();

        var editedComment = "Edited comment to " + UUID.randomUUID();
        ef.setText(editedComment);
        var prBtns = UIUtil.findComponentsOfType(contentComponent, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        var prBtn = prBtns.get(1);
        /* click the button to edit the patch */

        prBtn.doClick();
        executeUiTasks();
        // TODO verify patch comment was edited ...
    }

    @Test
    public void createNewPatchTest() throws InterruptedException {
        var newPatchPanel = new CreatePatchPanel(patchTabController, myProject, List.of(firstRepo)) {
            @Override
            public GitRemote findRadRemote(GitRepository repo, String myUrl) {
                return new GitRemote("test", List.of(), List.of(), List.of(), List.of());
            }
        };
        var tabs = newPatchPanel.create();

        var panel = (JPanel) tabs.getComponents()[6];
        var children = ((JComponent) panel.getComponents()[0]).getComponents();

        panel = (JPanel) children[0];
        children = ((JPanel) panel.getComponents()[0]).getComponents();

        var buttonsPanel = (JPanel) ((JPanel) children[2]).getComponents()[1];
        var titleField = UIUtil.findComponentOfType((JComponent) children[0], JBTextArea.class);
        final var patchName = "Test Patch";
        titleField.setText(patchName);
        var descriptionField = UIUtil.findComponentOfType((JComponent) children[1], JBTextArea.class);
        final var patchDesc = "Test Description";
        descriptionField.setText(patchDesc);

        // Get the panel where the actions select are
        var actionsPanel = (JPanel) ((JPanel) children[2]).getComponents()[0];
        var actionsPanelComponents = actionsPanel.getComponents();
        assertThat(((JLabel) actionsPanelComponents[0]).getText()).isEqualTo(RadicleBundle.message("label"));

        var labelSelect = newPatchPanel.getLabelSelect();
        var label1 = "label1";
        var label2 = "label2";
        labelSelect.storeLabels.add(label1);
        labelSelect.storeLabels.add(label2);

        // Find label panel and trigger the open action
        var labelPanel = (NonOpaquePanel) actionsPanelComponents[1];
        var labelButton = UIUtil.findComponentOfType(labelPanel, InlineIconButton.class);
        labelButton.getActionListener().actionPerformed(new ActionEvent(labelButton, 0, ""));
        executeUiTasks();

        var labelPopupListener = labelSelect.listener;
        var labelJbList = UIUtil.findComponentOfType(labelSelect.jbPopup.getContent(), JBList.class);
        var labelListModel = labelJbList.getModel();
        var fakePopup = JBPopupFactory.getInstance().createPopupChooserBuilder(new ArrayList<String>()).createPopup();
        fakePopup.getContent().removeAll();
        fakePopup.getContent().add(new BorderLayoutPanel());
        labelPopupListener.beforeShown(new LightweightWindowEvent(fakePopup));

        //Wait to load labels
        labelSelect.latch.await(5, TimeUnit.SECONDS);
        assertThat(labelListModel.getSize()).isEqualTo(2);

        // Find create new patch button
        var createPatchButton = UIUtil.findComponentOfType(buttonsPanel, JButton.class);
        createPatchButton.doClick();
        executeUiTasks();
        var res = radStub.commandsStr.poll();
        assertThat(res).contains(patchName);
        assertThat(res).contains(patchDesc);
        var labels = radStub.commandsStr.poll(10, TimeUnit.SECONDS);
        assertThat(labels).contains(label1);
        assertThat(labels).contains(label2);
    }

    @Test
    public void testChangeTitle() throws InterruptedException {
        var timelineComponent = patchEditorProvider.getTimelineComponent();
        var titlePanel = timelineComponent.getHeaderPanel();
        var editBtn = UIUtil.findComponentOfType(titlePanel, InlineIconButton.class);
        //send event that we clicked edit
        editBtn.getActionListener().actionPerformed(new ActionEvent(editBtn, 0, ""));
        executeUiTasks();

        var ef = UIUtil.findComponentOfType(titlePanel, DragAndDropField.class);
        /* Test the header title */
        assertThat(ef.getText()).isEqualTo(patch.title);

        markAsShowing(ef.getParent(), ef);
        executeUiTasks();
        var editedTitle = "Edited title to " + UUID.randomUUID();
        ef.setText(editedTitle);
        var prBtns = UIUtil.findComponentsOfType(titlePanel, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        var prBtn = prBtns.get(1);
        /* click the button to edit the patch */
        patch.title = editedTitle;
        prBtn.doClick();
        /* Wait for the reload */
        executeUiTasks();
        Thread.sleep(1000);
        var updatedPatchModel = patchTabController.getPatchModel();
        var updatedPatch = updatedPatchModel.getValue();
        assertThat(editedTitle).isEqualTo(updatedPatch.title);

        // Open createEditor
        patch.repo = firstRepo;
        patch.project = getProject();
        patchEditorProvider.createEditor(getProject(), editorFile);
        timelineComponent = patchEditorProvider.getTimelineComponent();
        titlePanel = timelineComponent.getHeaderPanel();
        editBtn = UIUtil.findComponentOfType(titlePanel, InlineIconButton.class);
        //send event that we clicked edit
        editBtn.getActionListener().actionPerformed(new ActionEvent(editBtn, 0, ""));
        executeUiTasks();
        ef = UIUtil.findComponentOfType(titlePanel, DragAndDropField.class);
        assertThat(ef.getText()).isEqualTo(editedTitle);
    }

    @Test
    public void testCopyButton() throws IOException, UnsupportedFlavorException {
        var patchProposalPanel = new PatchProposalPanel(patchTabController, new SingleValueModel<>(patch)) {
            @Override
            public void refreshVcs() {

            }
        };
        var panel = patchProposalPanel.createViewPatchProposalPanel();
        var ef = UIUtil.findComponentOfType(panel, OnePixelSplitter.class);
        var myPanel = ef.getFirstComponent();
        var mainPanel = (JPanel) myPanel.getComponents()[0];
        var copyButton = UIUtil.findComponentOfType(mainPanel, Utils.CopyButton.class);
        copyButton.doClick();
        var contents = ClipboardSynchronizer.getInstance().getContents();
        var patchId = (String) contents.getTransferData(DataFlavor.stringFlavor);
        assertThat(patchId).isEqualTo(patch.id);
    }

    @Test
    public void testCheckoutButton() throws InterruptedException {
        var patchProposalPanel = new PatchProposalPanel(patchTabController, new SingleValueModel<>(patch)) {
            @Override
            public void refreshVcs() {

            }
        };
        var panel = patchProposalPanel.createViewPatchProposalPanel();
        var ef = UIUtil.findComponentOfType(panel, OnePixelSplitter.class);
        var myPanel = ef.getFirstComponent();
        var mainPanel = (JPanel) myPanel.getComponents()[0];
        var opaquePanel = (JPanel) mainPanel.getComponents()[2];
        var checkoutButton = (JButton) opaquePanel.getComponents()[2];
        //clear previous commands
        clearCommandQueues();
        checkoutButton.doClick();
        var checkoutCommand = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(checkoutCommand);
        assertThat(checkoutCommand.getCommandLineString()).contains("patch checkout " + patch.id.substring(0, 6));
        assertThat(checkoutButton.isEnabled()).isFalse();
    }

    @Test
    public void addRemoveLabels() throws InterruptedException {
        var patchProposalPanel = patchTabController.getPatchProposalPanel();
        var panel = patchTabController.getPatchProposalJPanel();

        var ef = UIUtil.findComponentOfType(panel, OnePixelSplitter.class);
        var actionPanel = ef.getSecondComponent();
        var components = actionPanel.getComponents();
        var tagLabel = (JLabel) components[2];
        assertThat(tagLabel.getText()).isEqualTo(RadicleBundle.message("label"));

        var tagPanel = (NonOpaquePanel) components[3];
        var myPanel = (BorderLayoutPanel) tagPanel.getComponent(0);

        // Assert that the label has the selected tags
        var stateValueLabel = (JLabel) myPanel.getComponents()[0];
        assertThat(stateValueLabel.getText()).contains(String.join(",", patch.labels));

        // Find edit key and press it
        var openPopupButton = UIUtil.findComponentOfType(tagPanel, InlineIconButton.class);
        openPopupButton.getActionListener().actionPerformed(new ActionEvent(openPopupButton, 0, ""));
        executeUiTasks();

        var tagSelect = patchProposalPanel.getLabelSelect();
        var popupListener = tagSelect.listener;
        var jblist = UIUtil.findComponentOfType(tagSelect.jbPopup.getContent(), JBList.class);
        var listmodel = jblist.getModel();

        // Trigger beforeShown method
        var fakePopup = JBPopupFactory.getInstance().createPopupChooserBuilder(new ArrayList<String>()).createPopup();
        fakePopup.getContent().removeAll();
        fakePopup.getContent().add(new BorderLayoutPanel());

        popupListener.beforeShown(new LightweightWindowEvent(fakePopup));
        //Wait to load tags
        tagSelect.latch.await(5, TimeUnit.SECONDS);
        assertThat(listmodel.getSize()).isEqualTo(2);

        var firstTag = (SelectionListCellRenderer.SelectableWrapper<PatchProposalPanel.LabelSelect.Label>) listmodel.getElementAt(0);
        assertThat(firstTag.value.label()).isEqualTo(patch.labels.get(0));
        assertThat(firstTag.selected).isTrue();

        var secondTag = (SelectionListCellRenderer.SelectableWrapper<PatchProposalPanel.LabelSelect.Label>) listmodel.getElementAt(1);
        assertThat(secondTag.value.label()).isEqualTo(patch.labels.get(1));
        assertThat(secondTag.selected).isTrue();

        clearCommandQueues();
        //Remove first tag
        ((SelectionListCellRenderer.SelectableWrapper<?>) listmodel.getElementAt(0)).selected = false;
        popupListener.onClosed(new LightweightWindowEvent(tagSelect.jbPopup));
        executeUiTasks();
        var command = radStub.commandsStr.poll(5, TimeUnit.SECONDS);
        assertThat(command).contains("--delete " + CommandLineUtil.posixQuote(firstTag.value.label()));
    }

    @Test
    public void testReactions() throws InterruptedException {
        if (true) {
            logger.warn("reactions on patch timeline are disabled!");
            return;
        }
        executeUiTasks();
        var emojiJPanel = patchEditorProvider.getTimelineComponent().getComponentsFactory().getEmojiJPanel();
        var emojiLabel = UIUtil.findComponentOfType(emojiJPanel, JLabel.class);
        emojiLabel.getMouseListeners()[0].mouseClicked(null);

        var borderPanel = UIUtil.findComponentOfType(emojiJPanel, BorderLayoutPanel.class);
        var myEmojiLabel = ((JLabel) ((BorderLayoutPanel) ((JPanel) borderPanel.getComponent(1)).getComponent(1)).getComponent(0));
        assertThat(myEmojiLabel.getText()).isEqualTo(patch.getRevisionList().get(0).getDiscussions().get(0).reactions.get(0).emoji());

        // Make new reaction
        var emojiPanel = patchEditorProvider.getTimelineComponent().getComponentsFactory().getEmojiPanel();
        var popUp = emojiPanel.getEmojisPopUp();
        var jblist = UIUtil.findComponentOfType(popUp.getContent(), JBList.class);

        var popUpListener = emojiPanel.getPopupListener();
        popUpListener.beforeShown(new LightweightWindowEvent(JBPopupFactory.getInstance().createPopupChooserBuilder(new ArrayList<String>()).createPopup()));

        //Wait for the emojis to load
        emojiPanel.getLatch().await(5, TimeUnit.SECONDS);
        var listmodel = jblist.getModel();
        assertThat(listmodel.getSize()).isEqualTo(8);

        //Select the first emoji
        jblist.setSelectedIndex(0);
        jblist.getMouseListeners()[4].mouseClicked(null);
        var selectedEmoji =  jblist.getSelectedValue();
        var emoji = (Emoji) ((SelectionListCellRenderer.SelectableWrapper) selectedEmoji).value;
        executeUiTasks();
        // TODO: jrad: verify emoji added

        //Remove reaction
        borderPanel = UIUtil.findComponentOfType(emojiJPanel, BorderLayoutPanel.class);
        var reactorsPanel = ((JPanel) borderPanel.getComponents()[1]).getComponents()[1];
        var listeners = reactorsPanel.getMouseListeners();
        listeners[0].mouseClicked(null);
        executeUiTasks();
        // TODO: jrad: verify emoji removed
    }

    @Test
    public void testStateEditButtonWithMergedPatch() {
        patch.state = RadPatch.State.MERGED;
        patchTabController.createPatchProposalPanel(patch);
        var panel = patchTabController.getPatchProposalJPanel();
        var ef = UIUtil.findComponentOfType(panel, OnePixelSplitter.class);
        var actionPanel = ef.getSecondComponent();
        var components = actionPanel.getComponents();
        var statePanel = (NonOpaquePanel) components[1];

        // Assert that if the patch has status merged then the edit button is disable
        var openPopupButton = UIUtil.findComponentOfType(statePanel, InlineIconButton.class);
        assertThat(openPopupButton.isEnabled()).isFalse();
        assertThat(openPopupButton.getTooltip()).isEqualTo(RadicleBundle.message("patchStateChangeTooltip"));
    }

    @Test
    public void changeStateTest() throws InterruptedException {
        var patchProposalPanel = patchTabController.getPatchProposalPanel();
        var panel = patchTabController.getPatchProposalJPanel();

        var ef = UIUtil.findComponentOfType(panel, OnePixelSplitter.class);
        var actionPanel = ef.getSecondComponent();
        var components = actionPanel.getComponents();
        var stateLabel = (JLabel) components[0];
        assertThat(stateLabel.getText()).isEqualTo(RadicleBundle.message("state"));

        var statePanel = (NonOpaquePanel) components[1];
        var myPanel = (BorderLayoutPanel) statePanel.getComponent(0);

        // Assert that the label has the selected state
        var stateValueLabel = (JLabel) myPanel.getComponents()[0];
        assertThat(stateValueLabel.getText()).contains(patch.state.label);

        // Find edit key and press it
        var openPopupButton = UIUtil.findComponentOfType(statePanel, InlineIconButton.class);
        openPopupButton.getActionListener().actionPerformed(new ActionEvent(openPopupButton, 0, ""));
        executeUiTasks();

        var stateSelect = patchProposalPanel.getStateSelect();
        var popupListener = stateSelect.listener;
        var jblist = UIUtil.findComponentOfType(stateSelect.jbPopup.getContent(), JBList.class);
        var listmodel = jblist.getModel();

        // Trigger beforeShown method
        popupListener.beforeShown(new LightweightWindowEvent(JBPopupFactory.getInstance().createPopupChooserBuilder(new ArrayList<String>()).createPopup()));
        //Wait to load state
        stateSelect.latch.await(5, TimeUnit.SECONDS);
        assertThat(listmodel.getSize()).isEqualTo(3);

        var openState = (SelectionListCellRenderer.SelectableWrapper<PatchProposalPanel.StateSelect.State>) listmodel.getElementAt(0);
        assertThat(openState.value.label()).isEqualTo(RadPatch.State.OPEN.label);
        assertThat(openState.selected).isTrue();

        var draftState = (SelectionListCellRenderer.SelectableWrapper<PatchProposalPanel.StateSelect.State>) listmodel.getElementAt(1);
        assertThat(draftState.value.label()).isEqualTo(RadPatch.State.DRAFT.label);
        assertThat(draftState.selected).isFalse();

        var archivedState = (SelectionListCellRenderer.SelectableWrapper<PatchProposalPanel.StateSelect.State>) listmodel.getElementAt(2);
        assertThat(archivedState.value.label()).isEqualTo(RadPatch.State.ARCHIVED.label);
        assertThat(archivedState.selected).isFalse();

        // Change state to draft
        ((SelectionListCellRenderer.SelectableWrapper<?>) listmodel.getElementAt(0)).selected = false;
        ((SelectionListCellRenderer.SelectableWrapper<?>) listmodel.getElementAt(1)).selected = true;

        //Trigger close function in order to trigger the stub and verify the request
        popupListener.onClosed(new LightweightWindowEvent(stateSelect.jbPopup));
        executeUiTasks();
        var cmds = new ArrayList<GeneralCommandLine>();
        radStub.commands.drainTo(cmds);
        // var res = response.poll(5, TimeUnit.SECONDS);
        assertThat(cmds).anyMatch(cmd -> cmd.getCommandLineString().contains("rad patch ready " + patch.id + " --undo"));
    }

    @Test
    public void testDescSection() {
        var descSection = patchEditorProvider.getTimelineComponent().getComponentsFactory().getDescSection();
        var elements = UIUtil.findComponentsOfType(descSection, JEditorPane.class);
        var timeline = "";
        for (var el : elements) {
            timeline += el.getText();
        }
        var latestRevision = patch.getLatestRevision();
        assertThat(timeline).contains(latestRevision.getDescription());
    }

    @Test
    public void testRevSection() {
        executeUiTasks();
        var revisionSection = patchEditorProvider.getTimelineComponent().getRevisionSection();
        executeUiTasks();
        var elements = UIUtil.findComponentsOfType(revisionSection, BaseHtmlEditorPane.class);
        var comments = "";
        for (var el : elements) {
            comments += el.getText();
        }
        assertThat(comments).contains(patch.getRevisionList().get(0).id());
        assertThat(comments).contains(patch.getRevisionList().get(1).id());
    }

    @Test
    public void testCommentsExists() {
        executeUiTasks();
        var revisionSection = patchEditorProvider.getTimelineComponent().getRevisionSection();
        var elements = UIUtil.findComponentsOfType(revisionSection, JEditorPane.class);
        var comments = elements.stream().map(JEditorPane::getText).collect(Collectors.joining());
        assertThat(comments).contains(firstComment);
        assertThat(comments).contains(patch.getRevisionList().get(0).id());
        assertThat(comments).contains(secondComment);
        assertThat(comments).contains(patch.getRevisionList().get(1).id());
        assertThat(comments).contains(getExpectedTag(txtEmbed));
        assertThat(comments).contains(getExpectedTag(imgEmbed));
    }

    @Test
    public void testReviewExists() {
        executeUiTasks();
        var revisionSection = patchEditorProvider.getTimelineComponent().getRevisionSection();
        var elements = UIUtil.findComponentsOfType(revisionSection, JEditorPane.class);
        var comments = elements.stream().map(JEditorPane::getText).collect(Collectors.joining());
        assertThat(comments).contains(REVIEW_SUMMARY);
    }

    @Test
    public void testReplyComment() {
        // Clear previous commands
        clearCommandQueues();
        executeUiTasks();
        var replyPanel = patchEditorProvider.getTimelineComponent().getComponentsFactory().getReplyPanel();

        var replyButton = UIUtil.findComponentsOfType(replyPanel, LinkLabel.class);
        assertThat(replyButton.size()).isEqualTo(1);
        replyButton.get(0).doClick();

        var ef = UIUtil.findComponentOfType(replyPanel, DragAndDropField.class);
        assertThat(ef).isNotNull();
        markAsShowing(ef.getParent(), ef);
        assertThat(ef.getText()).isEmpty();
        executeUiTasks();
        ef.setText(replyComment);
        var prBtns = UIUtil.findComponentsOfType(replyPanel, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        var prBtn = prBtns.get(1);
        prBtn.doClick();
        executeUiTasks();
        var commandStr = radStub.commandsStr.poll();
        assertThat(commandStr).contains(replyComment);
    }

    @Test
    public void testComment() throws InterruptedException {
        // Clear previous commands
        clearCommandQueues();
        executeUiTasks();
        var timelineComponent = patchEditorProvider.getTimelineComponent();
        var commentPanel = timelineComponent.getCommentPanel();
        var ef = UIUtil.findComponentOfType(commentPanel, DragAndDropField.class);
        assertThat(ef).isNotNull();
        markAsShowing(ef.getParent(), ef);
        executeUiTasks();
        assertThat(ef.getText()).isEmpty();
        ef.setText(dummyComment);
        var prBtns = UIUtil.findComponentsOfType(commentPanel, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        var prBtn = prBtns.get(0);
        prBtn.doClick();
        executeUiTasks();
        Thread.sleep(1000);
        //Comment
        var commandStr = radStub.commandsStr.poll(1, TimeUnit.SECONDS);
        assertThat(commandStr).contains(patch.getLatestRevision().id());
        assertThat(commandStr).contains(dummyComment);
        var edit = new RadPatch.Edit(new RadAuthor("myTestAuthor", "myTestAlias"), dummyComment, Instant.now(), List.of());
        var edits = List.of(edit);
        var discussion = new RadDiscussion("542", new RadAuthor("myTestAuthor", "myTestAlias"),
                dummyComment, Instant.now(), "", List.of(), List.of(), null, edits);
        var discussionToEdit = patch.getLatestRevision().getDiscussions().get(0);
        patch.getLatestRevision().discussion().comments.put("542", discussion);


        // Open createEditor
        patch.repo = firstRepo;
        patch.project = getProject();
        patchEditorProvider.createEditor(getProject(), editorFile);
        clearCommandQueues();
        nativeStub.getCommands().clear();
        executeUiTasks();
        Thread.sleep(1000);

        var revisionSection = patchEditorProvider.getTimelineComponent().getRevisionSection();
        executeUiTasks();
        Thread.sleep(1000);

        var elements = UIUtil.findComponentsOfType(revisionSection, JEditorPane.class);
        assertThat(elements).isNotEmpty();
        var comments = elements.stream().map(JEditorPane::getText).collect(Collectors.joining());
        assertThat(comments).contains(firstComment);
        assertThat(comments).contains(patch.getRevisionList().get(0).id());
        assertThat(comments).contains(secondComment);
        assertThat(comments).contains(patch.getRevisionList().get(1).id());
        //Check that notification get triggered
        markAsShowing(ef.getParent(), ef);
        executeUiTasks();
        dummyComment = "break";
        ef.setText(dummyComment);
        prBtns = UIUtil.findComponentsOfType(commentPanel, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        prBtn = prBtns.get(1);
        prBtn.doClick();
        executeUiTasks();
        Thread.sleep(1000);
        executeUiTasks();
        var not = notificationsQueue.poll(20, TimeUnit.SECONDS);
        assertThat(not).isNotNull();
        assertThat(not.getTitle()).isEqualTo(RadicleBundle.message("radCliError"));

        // Test edit patch functionality
        if (true) {
            logger.warn("patch comment edit is disabled");
            return;
        }
        var commPanel = timelineComponent.getComponentsFactory().getCommentPanel();
        var editBtn = UIUtil.findComponentOfType(commPanel, InlineIconButton.class);
        editBtn.getActionListener().actionPerformed(new ActionEvent(editBtn, 0, ""));
        ef = UIUtil.findComponentOfType(commPanel, DragAndDropField.class);
        markAsShowing(ef.getParent(), ef);
        executeUiTasks();
        var editedComment = "Edited comment to " + UUID.randomUUID();
        ef.setText(editedComment);
        prBtns = UIUtil.findComponentsOfType(commPanel, JButton.class);
        assertThat(prBtns).hasSizeGreaterThanOrEqualTo(1);
        prBtn = prBtns.get(1);
        prBtn.doClick();
        executeUiTasks();
        var cmds = new ArrayList<GeneralCommandLine>();
        radStub.commands.drainTo(cmds);
        assertThat(cmds)
                .anyMatch(c -> c.getCommandLineString().contains(patch.getLatestRevision().id()) && c.getCommandLineString().contains(discussionToEdit.id));
    }

    private RadPatch createPatch() {
        var txtGitObjectId = UUID.randomUUID().toString();
        var imgGitObjectId = UUID.randomUUID().toString();

        txtEmbed = new Embed(txtGitObjectId, "test.txt", "git:" + txtGitObjectId);
        imgEmbed = new Embed(imgGitObjectId, "test.jpg", "git:" + imgGitObjectId);
        var txtEmbedMarkDown = "![" + txtEmbed.getName() + "](" + txtEmbed.getOid() + ")";
        var imgEmbedMarkDown = "![" + imgEmbed.getName() + "](" + imgEmbed.getOid() + ")";
        firstComment = "hello";
        secondComment = "hello back";
        var firstCommit = commitHistory.get(0);
        var secondCommit = commitHistory.get(1);
        var firstDiscussion = createDiscussion(firstComment + txtEmbedMarkDown + imgEmbedMarkDown, List.of(txtEmbed, imgEmbed));
        var secondDiscussion = createDiscussion(secondComment + txtEmbedMarkDown + imgEmbedMarkDown, List.of(txtEmbed, imgEmbed));
        var firstRev = createRevision("testRevision1", "testRevision1", firstCommit, firstDiscussion, Instant.now());
        var secondRev = createRevision("testRevision2", "testRevision1", secondCommit, secondDiscussion, Instant.now().plus(1, ChronoUnit.DAYS));
        var revMap = new HashMap<String, RadPatch.Revision>();
        revMap.put(firstRev.id(), firstRev);
        revMap.put(secondRev.id(), secondRev);
        var myPatch = new RadPatch(randomId(), new RadProject(randomId(), "test", "test", "main", List.of()), RadStub.SELF, "testPatch", RadStub.SELF,
                "testTarget", List.of("tag1", "tag2"), RadPatch.State.OPEN, revMap);
        myPatch.project = getProject();
        myPatch.repo = firstRepo;
        return myPatch;
    }

    private String getExpectedTag(Embed dummyEmbed) {
        var expectedUrl = dummyEmbed.getOid();
        if (dummyEmbed.getName().contains(".txt")) {
            return "<a href=\"" + expectedUrl + "\">" + dummyEmbed.getName() + "</a>";
        } else {
            return "<img src=\"" + expectedUrl + "\">";
        }
    }

    private PatchDiffWindow initializeDiffWindow(GitCommit commit) {
        var patchDiffWindow = new PatchDiffWindow();
        var diffContext = new DiffContext() {
            @Override
            public boolean isFocusedInWindow() {
                return true;
            }

            @Override
            public void requestFocusInWindow() {

            }

            @Override
            public @Nullable Project getProject() {
                return null;
            }

            @Override
            public boolean isWindowFocused() {
                return true;
            }
        };
        diffContext.putUserData(PatchComponentFactory.PATCH_DIFF, patch);
        var beforeDiffContent = DiffContentFactory.getInstance().create("My Change");
        var afterDiffContent = DiffContentFactory.getInstance().create("My Change 1");
        var req = new SimpleDiffRequest("Diff", beforeDiffContent, afterDiffContent, "", "");
        var firstChange = commit.getChanges().stream().findFirst().orElseThrow();
        req.putUserData(ChangeDiffRequestProducer.CHANGE_KEY, firstChange);
        var viewer = mock(TwosideTextDiffViewer.class);
        Document editorDocument = EditorFactory.getInstance().createDocument("");
        var editorFactory = new EditorFactoryImpl(null);
        var editor = (EditorEx) editorFactory.createEditor(editorDocument);
        when(viewer.getEditor(Side.RIGHT)).thenReturn(editor);
        when(viewer.getEditor(Side.LEFT)).thenReturn(editor);
        patchDiffWindow.onViewerCreated(viewer, diffContext, req);
        executeUiTasks();
        return patchDiffWindow;
    }

    public String findPath(VirtualFile file) {
        var repo = GitUtil.getRepositoryManager(getProject()).getRepositoryForFileQuick(file);
        var rootPath = repo.getRoot().getPath();
        return Paths.get(rootPath).relativize(Paths.get(file.getPath())).toString().replace("\\", "/");
    }

    private RadPatch.Revision createRevision(String id, String description, GitCommit commit, RadDiscussion discussion, Instant timestamp) {
        var firstChange = commit.getChanges().stream().findFirst().orElseThrow();
        var base = firstChange.getBeforeRevision().getRevisionNumber().asString();
        var discussions = new ArrayList<RadDiscussion>();
        discussions.add(discussion);
        var review = new RadPatch.Review(randomId(), randomAuthor(), RadPatch.Review.Verdict.ACCEPT, REVIEW_SUMMARY, null, Instant.now());

        var reviewMap = new HashMap<String, RadPatch.Review>();
        reviewMap.put(review.id(), review);

        var author = randomAuthor();
        var discMap = new HashMap<String, RadDiscussion>();
        for (var disc : discussions) {
            discMap.put(disc.id, disc);
        }

        return new RadPatch.Revision(id, author, List.of(new RadPatch.Edit(author, description, Instant.now(),
                List.of())), List.of(), base, commit.getId().asString(), List.of("branch"), timestamp,
                new RadPatch.DiscussionObj(discMap, List.of()), reviewMap);
    }

    private RadDiscussion createDiscussion(String body, List<Embed> embedList) {
        var edit = new RadPatch.Edit(randomAuthor(), body, Instant.now(), List.of());
        var edits = List.of(edit);
        return new RadDiscussion(randomId(), RadStub.SELF, body, Instant.now(), "",
                List.of(new Reaction("\uD83D\uDC4D", List.of(randomAuthor()))), embedList, null, edits);
    }

    private RadDiscussion createDiscussionWithLocation(String body, List<Embed> embedList, RadDiscussion.Location location) {
        var edit = new RadPatch.Edit(randomAuthor(), body, Instant.now(), List.of());
        var edits = List.of(edit);
        return new RadDiscussion(randomId(), RadStub.SELF, body, Instant.now(), "",
                List.of(new Reaction("\uD83D\uDC4D", List.of(randomAuthor()))), embedList, location, edits);
    }
}
