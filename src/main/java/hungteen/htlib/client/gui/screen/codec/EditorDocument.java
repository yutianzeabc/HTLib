package hungteen.htlib.client.gui.screen.codec;

/** 记录用户编辑的修订号，保存成功时只确认当时发送的草稿。 */
final class EditorDocument {

    private long revision;
    private long savedRevision;

    long revision() {
        return revision;
    }

    boolean isDirty() {
        return revision != savedRevision;
    }

    void userChanged() {
        revision++;
    }

    void markSaved(long requestRevision) {
        if (requestRevision == revision) {
            savedRevision = revision;
        }
    }

    /** 切换文档时分配新的修订号，使上一个文档的保存回执失效。 */
    void reset() {
        revision++;
        savedRevision = revision;
    }
}
