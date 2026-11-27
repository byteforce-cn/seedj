package cn.byteforce.seedj.infra.coord.file;

/**
 * coord KV 里的文件 sidecar：逻辑 key → blob objectId 与元数据。
 *
 * <p>只在本模块内使用，随模块版本演进，不承诺跨版本兼容（与锁记录、投递账本一致）。
 */
record CoordFileRecord(
        String key,
        String objectId,
        long size,
        String contentType,
        long revision,
        long createdAtEpochMilli) {}
