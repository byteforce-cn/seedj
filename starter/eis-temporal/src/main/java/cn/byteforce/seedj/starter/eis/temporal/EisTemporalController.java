package cn.byteforce.seedj.starter.eis.temporal;

import cn.byteforce.seedj.starter.eis.core.EisObjectRepository;
import cn.byteforce.seedj.starter.eis.core.EisObjectRow;
import cn.byteforce.seedj.starter.eis.core.EisObjectType;
import cn.byteforce.seedj.starter.eis.core.EisObjectTypeRegistry;
import cn.byteforce.seedj.starter.eis.core.EisRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * EIS 时点查询端点（{@code 02-operation-contract/query.openapi.yaml} +
 * {@code 03-time-contract/time-travel.api.yaml}）。
 *
 * <ul>
 *   <li>{@code GET /objects/{objectType}/{id}?as_of&recorded_as_of}——当前/时点版本；</li>
 *   <li>{@code GET /time-travel/objects/{objectType}/{id}?valid_at&recorded_at}——双时间轴查询；</li>
 *   <li>{@code GET /time-travel/objects/{objectType}/{id}/history?timeline&from&to}——版本历史。</li>
 * </ul>
 *
 * <p>半开区间 {@code [from, to)}（temporal.rules.yaml T-R002）；敏感列经
 * {@link EisObjectProjection} 剔除；无版本命中 → 404（EIS-404-001）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public class EisTemporalController {

    private final EisObjectRepository objects;

    private final EisObjectTypeRegistry objectTypes;

    /**
     * 构造时点查询端点。
     *
     * @param objects     对象存储
     * @param objectTypes 对象类型注册表
     */
    public EisTemporalController(EisObjectRepository objects, EisObjectTypeRegistry objectTypes) {
        this.objects = objects;
        this.objectTypes = objectTypes;
    }

    /**
     * 按 ID 查询（当前 / 业务时点 / 系统时点）。
     *
     * @param objectType   对象类型
     * @param id           对象 ID
     * @param asOf         业务时点（可空）
     * @param recordedAsOf 系统时点（可空）
     * @return 200 对象响应 / 404 无命中
     */
    @GetMapping("/objects/{objectType}/{id}")
    public ResponseEntity<Map<String, Object>> getObject(@PathVariable("objectType") String objectType,
                                                         @PathVariable("id") String id,
                                                         @RequestParam(value = "as_of", required = false) String asOf,
                                                         @RequestParam(value = "recorded_as_of", required = false)
                                                         String recordedAsOf) {
        EisObjectType type = requireType(objectType);
        EisObjectRow row = objects.asOf(objectType, parseUuid(id), parseTime(asOf), parseTime(recordedAsOf))
                .orElseThrow(() -> EisRequestException.notFound("时点无版本命中: " + objectType + "/" + id));
        return ResponseEntity.ok(EisObjectProjection.toResponse(row, type));
    }

    /**
     * 双时间轴版本查询。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param validAt    业务时间切点（可空）
     * @param recordedAt 系统时间切点（可空）
     * @return 200 版本响应 / 404 无命中
     */
    @GetMapping("/time-travel/objects/{objectType}/{id}")
    public ResponseEntity<Map<String, Object>> getVersion(@PathVariable("objectType") String objectType,
                                                          @PathVariable("id") String id,
                                                          @RequestParam(value = "valid_at", required = false)
                                                          String validAt,
                                                          @RequestParam(value = "recorded_at", required = false)
                                                          String recordedAt) {
        EisObjectType type = requireType(objectType);
        EisObjectRow row = objects.asOf(objectType, parseUuid(id), parseTime(validAt), parseTime(recordedAt))
                .orElseThrow(() -> EisRequestException.notFound("时点无版本命中: " + objectType + "/" + id));
        return ResponseEntity.ok(EisObjectProjection.toResponse(row, type));
    }

    /**
     * 版本历史。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param timeline   时间轴选择（valid / recorded / both，默认 both）
     * @param from       窗口起点（可空；半开区间左端）
     * @param to         窗口终点（可空；半开区间右端）
     * @return 200 版本列表
     */
    @GetMapping("/time-travel/objects/{objectType}/{id}/history")
    public ResponseEntity<Map<String, Object>> getHistory(@PathVariable("objectType") String objectType,
                                                          @PathVariable("id") String id,
                                                          @RequestParam(value = "timeline", defaultValue = "both")
                                                          String timeline,
                                                          @RequestParam(value = "from", required = false) String from,
                                                          @RequestParam(value = "to", required = false) String to) {
        EisObjectType type = requireType(objectType);
        String axis = timeline == null ? "both" : timeline;
        if (!"valid".equals(axis) && !"recorded".equals(axis) && !"both".equals(axis)) {
            throw EisRequestException.invalidPayload("timeline 非法: " + timeline);
        }
        Instant windowFrom = parseTime(from);
        Instant windowTo = parseTime(to);
        List<EisObjectRow> rows = objects.history(objectType, parseUuid(id));
        List<Map<String, Object>> versions = new ArrayList<>();
        for (EisObjectRow row : rows) {
            if (inWindow(row, axis, windowFrom, windowTo)) {
                versions.add(EisObjectProjection.toResponse(row, type));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versions", versions);
        return ResponseEntity.ok(body);
    }

    private static boolean inWindow(EisObjectRow row, String axis, Instant from, Instant to) {
        if ("recorded".equals(axis)) {
            return intersects(row.getRecordedAt(), row.getSupersededAt(), from, to);
        }
        if ("valid".equals(axis)) {
            return intersects(row.getValidFrom(), row.getValidTo(), from, to);
        }
        return true;
    }

    /**
     * 半开区间相交判定：[start, end) ∩ [from, to) ≠ ∅。
     */
    private static boolean intersects(Instant start, Instant end, Instant from, Instant to) {
        if (from != null && !(end == null || end.isAfter(from))) {
            return false;
        }
        return to == null || start.isBefore(to);
    }

    private EisObjectType requireType(String objectType) {
        EisObjectType type = objectTypes.get(objectType);
        if (type == null) {
            throw EisRequestException.notFound("未注册对象类型: " + objectType);
        }
        return type;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw EisRequestException.invalidPayload("对象 ID 非法: " + value);
        }
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw EisRequestException.invalidPayload("时间参数非法: " + value);
        }
    }
}
