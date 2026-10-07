package cn.byteforce.seedj.codegen;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CodeEnumProcessor} 契约测试：为 {@code @GenerateCodeEnum} 枚举生成静态工厂
 * Codec（生成代码必须可编译），非枚举标注报错。
 *
 * <p>测试自带 {@code cn.byteforce.seedj.core} 的最小桩源码，避免 codegen 反向依赖 core
 * （core 等模块以编译期注解处理器方式依赖 codegen）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CodeEnumProcessorTest {

    private static final JavaFileObject CODE_ENUM = JavaFileObjects.forSourceString(
            "cn.byteforce.seedj.core.CodeEnum", """
                    package cn.byteforce.seedj.core;

                    public interface CodeEnum<C> {
                        C getCode();
                        String getLabel();
                    }
                    """);

    private static final JavaFileObject CODE_ENUMS = JavaFileObjects.forSourceString(
            "cn.byteforce.seedj.core.CodeEnums", """
                    package cn.byteforce.seedj.core;

                    import java.util.Map;

                    public final class CodeEnums {
                        private CodeEnums() {
                        }

                        public static <E extends Enum<E> & CodeEnum<C>, C> E of(Class<E> enumClass, C code) {
                            return null;
                        }

                        public static <E extends Enum<E> & CodeEnum<C>, C> E ofRequired(Class<E> enumClass, C code) {
                            return null;
                        }

                        public static <E extends Enum<E> & CodeEnum<C>, C> Map<C, E> toMap(Class<E> enumClass) {
                            return null;
                        }
                    }
                    """);

    @Test
    void generatesCompilableCodecForAnnotatedEnum() throws Exception {
        JavaFileObject sample = JavaFileObjects.forSourceString("test.SampleState", """
                package test;

                import cn.byteforce.seedj.codegen.GenerateCodeEnum;
                import cn.byteforce.seedj.core.CodeEnum;

                @GenerateCodeEnum
                public enum SampleState implements CodeEnum<Integer> {
                    ON(1, "on");

                    private final Integer code;
                    private final String label;

                    SampleState(Integer code, String label) {
                        this.code = code;
                        this.label = label;
                    }

                    @Override
                    public Integer getCode() {
                        return code;
                    }

                    @Override
                    public String getLabel() {
                        return label;
                    }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new CodeEnumProcessor())
                .compile(CODE_ENUM, CODE_ENUMS, sample);

        CompilationSubject.assertThat(compilation).succeeded();

        JavaFileObject codec = compilation.generatedSourceFile("test.SampleStateCodec")
                .orElseThrow(() -> new AssertionError("未生成 test.SampleStateCodec"));
        String source = codec.getCharContent(true).toString();
        assertThat(source)
                .contains("public final class SampleStateCodec")
                .contains("public static SampleState of(java.lang.Integer code)")
                .contains("public static SampleState ofRequired(java.lang.Integer code)")
                .contains("public static Map<java.lang.Integer, SampleState> map()");
    }

    @Test
    void rejectsNonEnumAnnotatedTypes() {
        JavaFileObject classSource = JavaFileObjects.forSourceString("test.NotAnEnum", """
                package test;

                import cn.byteforce.seedj.codegen.GenerateCodeEnum;

                @GenerateCodeEnum
                public class NotAnEnum {
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new CodeEnumProcessor())
                .compile(CODE_ENUM, CODE_ENUMS, classSource);

        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation).hadErrorContaining("只能标注在枚举上");
    }
}
