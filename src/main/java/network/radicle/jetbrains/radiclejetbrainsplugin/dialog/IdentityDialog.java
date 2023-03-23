package network.radicle.jetbrains.radiclejetbrainsplugin.dialog;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JComponent;
import javax.swing.Action;
import javax.swing.event.DocumentEvent;

public class IdentityDialog extends DialogWrapper {
    private JPanel contentPane;
    public JTextField passphraseField;

    public IdentityDialog() {
        super(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return contentPane;
    }

    @Override
    protected void doOKAction() {
        super.doOKAction();
    }

    @Override
    public void doCancelAction() {
        super.doCancelAction();
    }

    @NotNull
    @Override
    protected Action[] createActions() {
        super.createDefaultActions();
        return new Action[]{getOKAction(), getCancelAction()};
    }

    protected void init() {
        super.init();
        var isFormValid =  !passphraseField.getText().isEmpty();
        setOKActionEnabled(isFormValid);
        var textFieldListener = new TextFieldListener();
        passphraseField.getDocument().addDocumentListener(textFieldListener);
    }

    public class TextFieldListener extends DocumentAdapter {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) {
            if (passphraseField.getText().isEmpty()) {
                setOKActionEnabled(false);
            } else {
                setOKActionEnabled(true);
            }
        }
    }
}
