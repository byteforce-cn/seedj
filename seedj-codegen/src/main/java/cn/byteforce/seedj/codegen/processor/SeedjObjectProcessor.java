package cn.byteforce.seedj.codegen.processor;

import cn.byteforce.seedj.codegen.MaskerName;
import cn.byteforce.seedj.codegen.SeedjField;
import cn.byteforce.seedj.codegen.SeedjObject;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * {@code @SeedjObject} 处理器：编译期生成 schema、OpenAPI 与迁移候选。
 *
 * <p>生成物：
 *
 * <ul>
 *   <li>{@code <Record>Schema}（包内可见的 {@code ObjectSchema} 工厂）；
 *   <li>{@code META-INF/seedj/openapi/<object>.yaml}；
 *   <li>{@code META-INF/seedj/ddl/<table>-create.sql} 与 {@code <table>-columns.sql}。
 * </ul>
 *
 * <p>错误在编译期暴露：非 record、非法标识符、元字段冲突、重复字段、不可映射的 Java 类型、 未知打码规则都会让编译失败，而不是生成半成品。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SupportedAnnotationTypes("cn.byteforce.seedj.codegen.SeedjObject")
public final class SeedjObjectProcessor extends AbstractProcessor {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver() || round.errorRaised()) {
            return false;
        }
        for (Element element : round.getElementsAnnotatedWith(SeedjObject.class)) {
            if (!(element instanceof TypeElement type) || type.getKind() != ElementKind.RECORD) {
                error(element, "@SeedjObject can only annotate a record");
                continue;
            }
            ObjectModel model = build(type, type.getAnnotation(SeedjObject.class));
            if (model == null) {
                continue;
            }
            emit(type, model);
        }
        return true;
    }

    private ObjectModel build(TypeElement type, SeedjObject declaration) {
        String objectType = declaration.objectType();
        String table = declaration.table();
        if (!IDENTIFIER.matcher(objectType).matches()) {
            error(type, "illegal objectType: " + objectType);
            return null;
        }
        if (!IDENTIFIER.matcher(table).matches()) {
            error(type, "illegal table: " + table);
            return null;
        }

        List<FieldModel> fields = new java.util.ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        boolean valid = true;
        for (RecordComponentElement component : type.getRecordComponents()) {
            SeedjField spec = component.getAnnotation(SeedjField.class);
            String name =
                    spec != null && !spec.name().isBlank()
                            ? spec.name()
                            : Names.snakeCase(component.getSimpleName().toString());
            if (!IDENTIFIER.matcher(name).matches()) {
                error(component, "illegal field name: " + name);
                valid = false;
                continue;
            }
            if (ObjectSchema.META_FIELDS.contains(name)) {
                error(component, "field clashes with a temporal meta field: " + name);
                valid = false;
                continue;
            }
            if (!names.add(name)) {
                error(component, "duplicate field: " + name);
                valid = false;
                continue;
            }
            String javaType = javaTypeName(component);
            FieldType fieldType = fieldType(component);
            if (fieldType == null) {
                error(
                        component,
                        "unsupported field type: "
                                + component.asType()
                                + "; use String, int/Integer, long/Long, BigDecimal/double/float,"
                                + " boolean/Boolean, LocalDate, Instant/OffsetDateTime, UUID or an enum");
                valid = false;
                continue;
            }
            cn.byteforce.seedj.domain.FieldDefinition.Constraints constraints =
                    constraints(spec, fieldType);
            if (constraints == null) {
                error(component, "DECIMAL field must declare precision (and optionally scale)");
                valid = false;
                continue;
            }
            try {
                new cn.byteforce.seedj.domain.FieldDefinition(
                        name,
                        fieldType,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        cn.byteforce.seedj.domain.Privacy.PUBLIC,
                        null,
                        constraints);
            } catch (IllegalArgumentException invalid) {
                error(component, invalid.getMessage());
                valid = false;
                continue;
            }
            fields.add(
                    new FieldModel(
                            name,
                            fieldType,
                            javaType,
                            spec != null && spec.required(),
                            spec != null && spec.writeOnly(),
                            spec != null && spec.filterable(),
                            spec != null && spec.sortable(),
                            spec != null && spec.indexed(),
                            spec != null
                                    ? spec.privacy()
                                    : cn.byteforce.seedj.domain.Privacy.PUBLIC,
                            spec != null ? spec.masker() : MaskerName.NONE,
                            constraints));
        }
        if (!valid) {
            return null;
        }
        if (fields.isEmpty()) {
            error(type, "@SeedjObject record must declare at least one component");
            return null;
        }
        String packageName =
                processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        return new ObjectModel(
                objectType,
                table,
                packageName,
                type.getSimpleName().toString(),
                declaration.view(),
                fields);
    }

    private static cn.byteforce.seedj.domain.FieldDefinition.Constraints constraints(
            SeedjField spec, FieldType type) {
        if (spec == null) {
            return type == FieldType.DECIMAL
                    ? null
                    : cn.byteforce.seedj.domain.FieldDefinition.Constraints.NONE;
        }
        java.util.Set<String> enums =
                new java.util.LinkedHashSet<>(java.util.Arrays.asList(spec.enumValues()));
        String pattern = spec.pattern() == null || spec.pattern().isBlank() ? null : spec.pattern();
        Integer minLength = spec.minLength() == 0 ? null : spec.minLength();
        Integer maxLength = spec.maxLength() == 0 ? null : spec.maxLength();
        Integer precision = spec.precision() == 0 ? null : spec.precision();
        Integer scale = precision == null ? null : spec.scale();
        if (type == FieldType.DECIMAL && precision == null) {
            return null;
        }
        return new cn.byteforce.seedj.domain.FieldDefinition.Constraints(
                enums, pattern, minLength, maxLength, spec.unique(), precision, scale);
    }

    private static String javaTypeName(RecordComponentElement component) {
        TypeMirror mirror = component.asType();
        if (mirror.getKind().isPrimitive()) {
            return mirror.toString();
        }
        if (mirror.getKind() == TypeKind.DECLARED) {
            return ((TypeElement) ((DeclaredType) mirror).asElement())
                    .getQualifiedName()
                    .toString();
        }
        return mirror.toString();
    }

    private FieldType fieldType(RecordComponentElement component) {
        TypeMirror mirror = component.asType();
        boolean enumType =
                mirror.getKind() == TypeKind.DECLARED
                        && ((DeclaredType) mirror).asElement().getKind() == ElementKind.ENUM;
        String typeName;
        if (mirror.getKind().isPrimitive()) {
            typeName = mirror.toString();
        } else if (mirror.getKind() == TypeKind.DECLARED) {
            typeName =
                    ((TypeElement) ((DeclaredType) mirror).asElement())
                            .getQualifiedName()
                            .toString();
        } else {
            return null;
        }
        return TypeMapping.infer(typeName, enumType);
    }

    private void emit(TypeElement origin, ObjectModel model) {
        Filer filer = processingEnv.getFiler();
        String qualified =
                model.packageName().isEmpty()
                        ? model.schemaClassName()
                        : model.packageName() + "." + model.schemaClassName();
        try {
            JavaFileObject source = filer.createSourceFile(qualified, origin);
            try (Writer writer = source.openWriter()) {
                writer.write(SchemaRenderer.javaSource(model));
            }
        } catch (IOException failure) {
            error(
                    origin,
                    "cannot write generated schema " + qualified + ": " + failure.getMessage());
            return;
        }
        if (model.view()) {
            writeView(origin, model);
        }
        write(
                filer,
                origin,
                "META-INF/seedj/openapi/" + model.objectType() + ".yaml",
                SchemaRenderer.openApi(model));
        write(
                filer,
                origin,
                "META-INF/seedj/ddl/" + model.table() + "-create.sql",
                SchemaRenderer.createSql(model));
        write(
                filer,
                origin,
                "META-INF/seedj/ddl/" + model.table() + "-columns.sql",
                SchemaRenderer.alterSql(model));
    }

    private void writeView(TypeElement origin, ObjectModel model) {
        Filer filer = processingEnv.getFiler();
        String qualified =
                model.packageName().isEmpty()
                        ? model.viewClassName()
                        : model.packageName() + "." + model.viewClassName();
        try {
            JavaFileObject source = filer.createSourceFile(qualified, origin);
            try (Writer writer = source.openWriter()) {
                writer.write(SchemaRenderer.viewSource(model));
            }
        } catch (IOException failure) {
            error(origin, "cannot write generated view " + qualified + ": " + failure.getMessage());
        }
    }

    private void write(Filer filer, Element origin, String path, String content) {
        try {
            FileObject resource =
                    filer.createResource(StandardLocation.CLASS_OUTPUT, "", path, origin);
            try (OutputStream out = resource.openOutputStream()) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (FilerException alreadyWritten) {
            // 增量/重复 round 会重新打开同一资源；内容由源 record 确定，保持已有文件即可。
        } catch (IOException failure) {
            error(origin, "cannot write generated resource " + path + ": " + failure.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv
                .getMessager()
                .printMessage(javax.tools.Diagnostic.Kind.ERROR, message, element);
    }
}
