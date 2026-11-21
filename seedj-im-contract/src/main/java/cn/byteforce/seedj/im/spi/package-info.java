/**
 * IM 存储端口：会话/成员、消息、游标、序号。
 *
 * <p>端口由 {@code seedj-infra-local}（内存）与 {@code seedj-jdbc-postgresql}（PostgreSQL）实现， 装配在 {@code
 * seedj-im-starter}。契约测试对两套实现跑同一组用例。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.im.spi;
