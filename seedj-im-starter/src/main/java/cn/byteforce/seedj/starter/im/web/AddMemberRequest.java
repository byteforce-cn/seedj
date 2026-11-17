package cn.byteforce.seedj.starter.im.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 加人请求。
 *
 * @param subjectId 被加入的 subject
 * @param role 角色：{@code OWNER} / {@code ADMIN} / {@code MEMBER}；空视为普通成员
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AddMemberRequest(String subjectId, String role) {}
