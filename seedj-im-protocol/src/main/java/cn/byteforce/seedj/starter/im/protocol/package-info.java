/**
 * IM 线路协议：命令字、帧与 27 个 body 类型、JSON 编解码。
 *
 * <p><b>职责</b>：客户端与服务端共享的协议契约；只依赖 Jackson，不依赖 Netty、Spring 或 服务端 starter，客户端可单独引入本制品。
 *
 * <p><b>依赖方向</b>：JDK + Jackson；不依赖 {@code core}、{@code seedj-im-starter} 或任何 实现模块。帧头校验与 Netty
 * 字节缓冲留在 {@code seedj-im-starter} 的 transport 包。
 */
package cn.byteforce.seedj.starter.im.protocol;
