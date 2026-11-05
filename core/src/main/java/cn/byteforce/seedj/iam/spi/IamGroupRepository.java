package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamGroupQuery;
import cn.byteforce.seedj.iam.IamPage;

import java.util.Optional;
import java.util.Set;

/** SCIM canonical group store. */
public interface IamGroupRepository {

    Optional<IamGroup> findById(String id);

    Optional<IamGroup> findByDisplayName(String tenantId, String displayName);

    IamPage<IamGroup> find(IamGroupQuery query);

    IamGroup create(IamGroup group);

    IamGroup update(IamGroup group);

    void delete(String id);

    Set<String> findMemberIds(String groupId);

    Set<String> findGroupIdsForUser(String userId);

    void addMember(String groupId, String userId);

    void removeMember(String groupId, String userId);
}
