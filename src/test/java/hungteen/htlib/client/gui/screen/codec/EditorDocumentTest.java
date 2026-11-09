package hungteen.htlib.client.gui.screen.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorDocumentTest {

    @Test
    void onlySavingTheCurrentRevisionClearsLaterEdits() {
        EditorDocument document = new EditorDocument();
        assertFalse(document.isDirty());

        document.userChanged();
        long submitted = document.revision();
        document.userChanged();
        document.markSaved(submitted);
        assertTrue(document.isDirty());

        document.markSaved(document.revision());
        assertFalse(document.isDirty());
        document.markSaved(submitted);
        assertFalse(document.isDirty());
    }

    @Test
    void previousDocumentsSaveCannotClearTheNewDraft() {
        EditorDocument document = new EditorDocument();
        document.userChanged();
        long submitted = document.revision();

        document.reset();
        assertFalse(document.isDirty());
        document.userChanged();
        document.markSaved(submitted);
        assertTrue(document.isDirty());
    }
}
