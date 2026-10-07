package cn.byteforce.seedj.security.core;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;


/**
 * seedj 用户详情模型：在 Spring Security {@link UserDetails} 基础上补充用户 / 租户 / 组织信息，
 * 供资源服务（JWT claims → 身份注入）与授权服务（令牌身份 claim 注入）共用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CustomUserDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    /** 用户 ID。 */
    private final Long userId;

    /** 登录用户名。 */
    private final String username;

    /** 密码（已编码；JWT 无状态场景通常为空）。 */
    private final String password;

    /** 昵称。 */
    private final String nickname;

    /** 邮箱。 */
    private final String email;

    /** 租户 ID。 */
    private final Long tenantId;

    /** 部门。 */
    private final String department;

    /** 权限集合。 */
    private final Collection<? extends GrantedAuthority> authorities;

    /**
     * 全参构造。
     *
     * @param userId      用户 ID
     * @param username    登录用户名
     * @param password    密码（已编码，可为空）
     * @param nickname    昵称
     * @param email       邮箱
     * @param tenantId    租户 ID
     * @param department  部门
     * @param authorities 权限集合
     */
    public CustomUserDetails(Long userId, String username, String password,
                             String nickname, String email, Long tenantId,
                             String department,
                             Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
        this.email = email;
        this.tenantId = tenantId;
        this.department = department;
        // 防御性快照 + 不可变包装（EI_EXPOSE_REP 类问题；UserDetails 契约为非 null）
        this.authorities = (authorities == null)
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(authorities));
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID
     */
    public Long getUserId() { return userId; }

    /**
     * 获取昵称。
     *
     * @return 昵称
     */
    public String getNickname() { return nickname; }

    /**
     * 获取邮箱。
     *
     * @return 邮箱
     */
    public String getEmail() { return email; }

    /**
     * 获取租户 ID。
     *
     * @return 租户 ID
     */
    public Long getTenantId() { return tenantId; }

    /**
     * 获取部门。
     *
     * @return 部门
     */
    public String getDepartment() { return department; }

    /**
     * 获取权限集合（不可变副本，保持 {@code List} 语义）。
     *
     * @return 权限集合
     */
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.unmodifiableList(new ArrayList<>(authorities));
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}