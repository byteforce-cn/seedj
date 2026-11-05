package cn.byteforce.seedj.transit;

public final class BatchResult<T> {
    private final String reference; private final T value; private final TransitErrorCode errorCode; private final String errorMessage;

    public BatchResult(String reference, T value, TransitErrorCode errorCode, String errorMessage) {
        this.reference = Validation.requireReference(reference);
        if (errorCode == null) {
            if (value == null || errorMessage != null) throw new IllegalArgumentException("成功时 value 必须非 null，且 errorMessage 必须为 null");
        } else {
            if (value != null) throw new IllegalArgumentException("失败时 value 必须为 null");
        }
        this.value = value; this.errorCode = errorCode; this.errorMessage = errorMessage;
    }
    public String getReference() { return reference; } public T getValue() { return value; }
    public TransitErrorCode getErrorCode() { return errorCode; } public String getErrorMessage() { return errorMessage; }
    @Override public String toString() { return "BatchResult{reference='" + reference + "', errorCode=" + errorCode + "}"; }
}
