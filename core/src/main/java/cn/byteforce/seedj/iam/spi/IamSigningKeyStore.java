package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamSigningKey;
import java.util.List;

/**
 * JWK persistence. The active key signs; retired keys remain published until their tokens have
 * expired.
 */
public interface IamSigningKeyStore {

    IamSigningKey activeKey();

    List<IamSigningKey> keys();

    IamSigningKey rotate();
}
