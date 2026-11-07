package cn.byteforce.seedj.starter.iam.identity;

import cn.byteforce.seedj.iam.IamSubjectAuthorization;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Spring Security adapter over the IAM user repository. */
public class IamUserDetailsService implements UserDetailsService {

    private final IamUserRepository users;
    private final IamAuthorizationService authorization;
    private final String tenantId;

    public IamUserDetailsService(
            IamUserRepository users, IamAuthorizationService authorization, String tenantId) {
        this.users = users;
        this.authorization = authorization;
        this.tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        IamUser user =
                users.findByUserName(tenantId, username)
                        .orElseThrow(() -> new UsernameNotFoundException("user not found"));
        String password =
                users.findPasswordHash(user.id())
                        .orElseThrow(() -> new UsernameNotFoundException("user has no credential"));
        IamSubjectAuthorization auth = authorization.effective(user.tenantId(), user.id());
        List<GrantedAuthority> authorities = new ArrayList<>();
        auth.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.permissions()
                .forEach(
                        permission ->
                                authorities.add(new SimpleGrantedAuthority("PERM_" + permission)));
        auth.groups()
                .forEach(group -> authorities.add(new SimpleGrantedAuthority("GROUP_" + group)));
        return User.withUsername(user.userName())
                .password(password)
                .authorities(authorities)
                .disabled(!user.active())
                .build();
    }
}
