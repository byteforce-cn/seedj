package cn.byteforce.seedj.infra.coord.file;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.seedj.file.FileErrorCode;
import cn.byteforce.seedj.file.FileException;

/** coord 对象存储错误码到文件错误码的唯一映射点。 */
final class CoordFileErrors {

    private CoordFileErrors() {}

    static FileException file(CoordException e, String endpoint, String action) {
        FileErrorCode mapped =
                switch (e.getErrorCode()) {
                    case NOT_FOUND -> FileErrorCode.NOT_FOUND;
                    case ALREADY_EXISTS -> FileErrorCode.ALREADY_EXISTS;
                    case INVALID_ARGUMENT, OUT_OF_RANGE -> FileErrorCode.INVALID_REQUEST;
                    case MESSAGE_TOO_LARGE, QUOTA_EXCEEDED -> FileErrorCode.TOO_LARGE;
                    case RESOURCE_EXHAUSTED,
                                    UNAVAILABLE,
                                    DEADLINE_EXCEEDED,
                                    AGENT_UNAVAILABLE,
                                    NOT_LEADER,
                                    SAFE_TS_LAG,
                                    EPOCH_STALE,
                                    HISTORY_UNAVAILABLE,
                                    TXN_NOT_FOUND,
                                    TXN_CONFLICT,
                                    TXN_CAS_FAILED,
                                    TXN_TOO_LARGE,
                                    READ_SET_TOO_LARGE,
                                    COMMIT_REJECTED,
                                    FAILED_PRECONDITION ->
                            FileErrorCode.PROVIDER_UNAVAILABLE;
                    default -> FileErrorCode.INTERNAL_ERROR;
                };
        return new FileException(
                mapped,
                "coord file " + action + " failed at " + endpoint + ": " + e.getErrorCode(),
                e);
    }
}
