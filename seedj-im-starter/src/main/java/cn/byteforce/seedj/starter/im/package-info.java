/**
 * IM 长连接装配：协议编解码、Netty TCP/WebSocket 传输与 JWT 握手。
 *
 * <p><b>职责</b>：把 IAM 签发的访问令牌映射为 {@code Caller}，维护连接注册表，并让宿主用
 * {@code @EnableImServer} 一键启动 TCP/WebSocket 长连接。业务对象与命令不在此模块。
 *
 * <p><b>依赖方向</b>：{@code seedj-biz-starter} → 本模块 → 宿主；本模块只消费令牌，不依赖
 * {@code seedj-iam-starter}。
 */
package cn.byteforce.seedj.starter.im;
