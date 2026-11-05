package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.IamUserQuery;

import java.time.Instant;
import java.util.Optional;

/** Persistent user + credential store. Implementations must enforce tenant+userName uniqueness. */
public interface IamUserRepository {

    Optional<IamUser> findById(String id);

    Optional<IamUser> findByUserName(String tenantId, String userName);

    IamPage<IamUser> find(IamUserQuery query);

    IamUser create(IamUser user, String passwordHash);

    /** Optimistic update; throws {@code IamException(CONFLICT)} on version mismatch. */
    IamUser update(IamUser user);

    void delete(String id);

    Optional<String> findPasswordHash(String userId);

    void updatePassword(String userId, String passwordHash, Instant changedAt);

    long count();
}
