package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.TaskId;

import java.util.List;

/** 绑定在原任务 COMPLETED 上的 AFTER 待派生记录。 */
record AfterPending(TaskId originalTaskId, List<ParticipantSpec> assignees, String actionId) {
    AfterPending {
        assignees = List.copyOf(assignees);
    }
}
