/**
 * AI 能力的启用注解：宿主用 {@code @EnableAIServer} 打开本模块。
 *
 * <p><b>职责</b>：只提供注解本身，把「是否启用 AI 能力」表达成一次显式选择；注解不做组件扫描，也不启动任何线程或
 * 端口。
 *
 * <p><b>依赖方向</b>：本包依赖同模块的 {@code cn.byteforce.seedj.starter.ai}（引入标记配置）与 {@code
 * seedj-biz-starter} 的 biz 标记；禁止依赖任何宿主模块。
 */
package cn.byteforce.seedj.starter.ai.annotation;
