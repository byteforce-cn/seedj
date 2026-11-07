package cn.byteforce.seedj.infra.local.workflow;

import java.time.Instant;

/** 前加签期间的 dueAt 冻结信息。 */
record Suspension(Instant suspendedAt, Instant originalDueAt) {}
