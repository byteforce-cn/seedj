package cn.byteforce.seedj.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Set;


/**
 * {@code @GenerateCodeEnum} 注解处理器：为标注的 CodeEnum 枚举生成
 * {@code <EnumName>Codec} 静态工厂（输出到 {@code target/generated-sources}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SupportedAnnotationTypes("cn.byteforce.seedj.codegen.GenerateCodeEnum")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class CodeEnumProcessor extends AbstractProcessor {

    /** CodeEnum 接口的全限定名 */
    private static final String CODE_ENUM_FQN = "cn.byteforce.seedj.core.CodeEnum";

    /** CodeEnums 工具类的全限定名 */
    private static final String CODE_ENUMS_FQN = "cn.byteforce.seedj.core.CodeEnums";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != ElementKind.ENUM) {
                    error(element, "@GenerateCodeEnum 只能标注在枚举上");
                    continue;
                }
                TypeElement enumElement = (TypeElement) element;
                try {
                    generate(enumElement);
                } catch (IOException e) {
                    error(element, "生成失败: " + e.getMessage());
                }
            }
        }
        return true;
    }

    private void generate(TypeElement enumElement) throws IOException {
        TypeMirror codeType = findCodeType(enumElement);
        if (codeType == null) {
            error(enumElement, "枚举未实现 " + CODE_ENUM_FQN + " 接口");
            return;
        }

        String pkg = processingEnv.getElementUtils()
                .getPackageOf(enumElement).getQualifiedName().toString();
        String enumName = enumElement.getSimpleName().toString();
        GenerateCodeEnum anno = enumElement.getAnnotation(GenerateCodeEnum.class);
        String suffix = (anno != null) ? anno.suffix() : "Codec";
        String codecName = enumName + suffix;
        String codeTypeName = codeType.toString();

        // 3. 生成源码
        String source = buildSource(pkg, enumName, codecName, codeTypeName);

        // 4. 写入
        Filer filer = processingEnv.getFiler();
        String qualifiedName = pkg.isEmpty() ? codecName : pkg + "." + codecName;
        JavaFileObject file = filer.createSourceFile(qualifiedName, enumElement);
        try (Writer writer = file.openWriter()) {
            writer.write(source);
        }

        note(enumElement, "已生成 " + qualifiedName);
    }

    private TypeMirror findCodeType(TypeElement enumElement) {
        for (TypeMirror iface : enumElement.getInterfaces()) {
            if (iface instanceof DeclaredType dt) {
                TypeElement ifaceElem = (TypeElement) dt.asElement();
                if (ifaceElem.getQualifiedName().contentEquals(CODE_ENUM_FQN)) {
                    List<? extends TypeMirror> args = dt.getTypeArguments();
                    if (!args.isEmpty()) {
                        return args.get(0);  // C 始终是第一个类型参数
                    }
                }
            }
        }
        return null;
    }

    private String buildSource(String pkg, String enumName,
                               String codecName, String codeTypeName) {
        StringBuilder sb = new StringBuilder(1024);

        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }

        sb.append("import ").append(CODE_ENUMS_FQN).append(";\n");
        sb.append("import java.util.Map;\n\n");

        sb.append("/**\n");
        sb.append(" * {@link ").append(enumName).append("} 的静态工厂方法。\n");
        sb.append(" *\n");
        sb.append(" * <p>由 byteforce-codegen 自动生成，请勿手动修改。</p>\n");
        sb.append(" *\n");
        sb.append(" * @author ByteForce\n");
        sb.append(" * @since 1.0.0\n");
        sb.append(" */\n");
        sb.append("public final class ").append(codecName).append(" {\n\n");

        // 私有构造
        sb.append("    private ").append(codecName).append("() {\n    }\n\n");

        // of
        sb.append("    /**\n");
        sb.append("     * 根据 code 查找枚举，找不到返回 {@code null}。\n");
        sb.append("     *\n");
        sb.append("     * @param code 枚举 code\n");
        sb.append("     * @return 匹配的枚举；无匹配时返回 {@code null}\n");
        sb.append("     */\n");
        sb.append("    public static ").append(enumName).append(" of(")
                .append(codeTypeName).append(" code) {\n");
        sb.append("        return CodeEnums.of(").append(enumName).append(".class, code);\n");
        sb.append("    }\n\n");

        // ofRequired
        sb.append("    /**\n");
        sb.append("     * 根据 code 查找枚举，找不到抛 {@link IllegalArgumentException}。\n");
        sb.append("     *\n");
        sb.append("     * @param code 枚举 code\n");
        sb.append("     * @return 匹配的枚举\n");
        sb.append("     */\n");
        sb.append("    public static ").append(enumName).append(" ofRequired(")
                .append(codeTypeName).append(" code) {\n");
        sb.append("        return CodeEnums.ofRequired(").append(enumName).append(".class, code);\n");
        sb.append("    }\n\n");

        // map
        sb.append("    /**\n");
        sb.append("     * 返回 code -> 枚举 的不可变 Map。\n");
        sb.append("     *\n");
        sb.append("     * @return 不可变映射\n");
        sb.append("     */\n");
        sb.append("    public static Map<").append(codeTypeName).append(", ")
                .append(enumName).append("> map() {\n");
        sb.append("        return CodeEnums.toMap(").append(enumName).append(".class);\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void note(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message, element);
    }
}