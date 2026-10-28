package com.xhr.geometryhelperbackend.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

import com.xhr.geometryhelperbackend.config.FollowupProperties;
import com.xhr.geometryhelperbackend.dto.FollowupMessage;
import com.xhr.geometryhelperbackend.dto.HistoryMessage;
import com.xhr.geometryhelperbackend.dto.HistoryPage;
import com.xhr.geometryhelperbackend.dto.QuestionDetail;
import com.xhr.geometryhelperbackend.dto.SolutionPart;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 几何题目历史记录服务。
 *
 * <p>负责把题目、识别过程、分步讲解和追问写入 PostgreSQL，并提供历史查询。
 * 当前还没有登录功能，所有记录统一归属 {@code user_id = 0} 的匿名用户。</p>
 */
@Service
public class QuestionHistoryService {

    /**
     * 当前默认用户 ID，后续接入登录后由认证信息替换。
     */
    private static final long DEFAULT_USER_ID = 0L;

    /**
     * 历史列表单页最大条数，避免一次返回过多模型正文。
     */
    private static final int MAX_PAGE_SIZE = 50;

    private static final int DEFAULT_PAGE_SIZE = 10;

    private static final String QUESTION_COLUMNS = """
            q.id, q.user_id, q.question_text, q.image_content_type, q.image_filename,
            (q.image_data IS NOT NULL) AS has_image, q.created_at
            """;

    private static final String MESSAGE_COLUMNS = """
            m.id, m.question_id, m.sequence_no, m.role, m.phase, m.message_type,
            m.step_no, m.model_name, m.content, m.reasoning, m.input_tokens,
            m.output_tokens, m.duration_ms, m.created_at
            """;

    private static final RowMapper<QuestionRow> QUESTION_ROW_MAPPER = (rs, rowNum) ->
            new QuestionRow(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    rs.getString("question_text"),
                    rs.getString("image_content_type"),
                    rs.getString("image_filename"),
                    rs.getBoolean("has_image"),
                    rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<MessageRow> MESSAGE_ROW_MAPPER = (rs, rowNum) ->
            new MessageRow(
                    rs.getLong("question_id"),
                    new HistoryMessage(
                            rs.getLong("id"),
                            rs.getInt("sequence_no"),
                            rs.getString("role"),
                            rs.getString("phase"),
                            rs.getString("message_type"),
                            rs.getObject("step_no", Integer.class),
                            rs.getString("model_name"),
                            rs.getString("content"),
                            rs.getString("reasoning"),
                            rs.getObject("input_tokens", Integer.class),
                            rs.getObject("output_tokens", Integer.class),
                            rs.getObject("duration_ms", Long.class),
                            rs.getObject("created_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private final ObjectMapper objectMapper;

    private final FollowupProperties followupProperties;

    public QuestionHistoryService(JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ObjectMapper objectMapper,
            FollowupProperties followupProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
        this.followupProperties = followupProperties;
    }

    /**
     * 新建题目并保存第一次识别请求和识别结果。
     *
     * @param file     用户上传的原始图片
     * @param userNote 用户补充说明
     * @param result   视觉模型识别结果
     * @return 新题目 ID
     */
    @Transactional
    public long createQuestionWithRecognition(MultipartFile file, String userNote,
            ModelCallResult result) {
        ensureAnonymousUser();
        long questionId = insertQuestion(file, result.answer());
        appendRecognitionMessages(questionId, userNote, result, false);
        return questionId;
    }

    /**
     * 重新识别时追加新的识别消息，并更新题目复述。
     *
     * @param questionId 已有题目 ID，可为空；为空时新建题目
     * @param file       原始图片
     * @param userNote   用户补充说明
     * @param result     重新识别结果
     * @return 题目 ID
     */
    @Transactional
    public long saveRecognition(Long questionId, MultipartFile file, String userNote,
            ModelCallResult result) {
        if (questionId == null) {
            return createQuestionWithRecognition(file, userNote, result);
        }

        lockQuestion(questionId);
        this.jdbcTemplate.update(
                "UPDATE geometry_question SET question_text = ? WHERE id = ? AND user_id = ?",
                result.answer(), questionId, DEFAULT_USER_ID);
        appendRecognitionMessages(questionId, userNote, result, true);
        return questionId;
    }

    /**
     * 确保题目记录存在。正常流程中题目在识别阶段已经创建；兼容旧前端或
     * 未携带题目 ID 的请求时，会用当前图片和题目复述补建一条记录。
     *
     * @param questionId 已有题目 ID，可为空
     * @param file       原始图片
     * @param analysis   用户确认过的题目复述
     * @return 题目 ID
     */
    @Transactional
    public long ensureQuestion(Long questionId, MultipartFile file, String analysis) {
        if (!StringUtils.hasText(analysis)) {
            throw new IllegalArgumentException("请先确认题目复述，再开始解题");
        }

        if (questionId != null) {
            lockQuestion(questionId);
            this.jdbcTemplate.update(
                    "UPDATE geometry_question SET question_text = ? WHERE id = ? AND user_id = ?",
                    analysis.trim(), questionId, DEFAULT_USER_ID);
            return questionId;
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("缺少题目图片，无法保存解题记录");
        }

        ensureAnonymousUser();
        return insertQuestion(file, analysis.trim());
    }

    /**
     * 保存一段讲解的请求和模型回答。
     *
     * @param questionId 题目 ID
     * @param part       段落序号
     * @param userNote   用户补充说明
     * @param result     模型回答
     */
    @Transactional
    public void appendSolutionPart(long questionId, int part, String userNote,
            ModelCallResult result) {
        if (part < 1) {
            throw new IllegalArgumentException("讲解段落必须从 1 开始");
        }

        lockQuestion(questionId);
        long sequenceNo = nextSequence(questionId);

        insertMessage(questionId, sequenceNo++, "user", "solve", "solution_request",
                part, null, solutionRequestContent(userNote, part), null, null, null, null,
                requestExtra(userNote, "solve", part));
        insertMessage(questionId, sequenceNo, "assistant", "solve", "solution_result",
                part, result.modelName(), result.answer(), result.reasoning(),
                result.inputTokens(), result.outputTokens(), result.durationMs(),
                resultExtra(result));
    }

    /**
     * 保存一次追问和模型回复。
     *
     * @param questionId 题目 ID
     * @param step       提问时用户已经展开到的讲解段落
     * @param question   用户追问
     * @param result     模型回复
     * @return 保存后的追问信息
     */
    @Transactional
    public FollowupRecord appendFollowup(long questionId, int step, String question,
            ModelCallResult result) {
        lockQuestion(questionId);

        // 控制器在调用模型前已经检查过一次；这里在行锁内再检查一次，
        // 防止并发请求同时通过前置检查后写入超过上限的追问。
        int maxFollowups = maxFollowupQuestions();
        if (maxFollowups == 0) {
            throw new IllegalArgumentException("当前未开启追问功能");
        }
        if (countFollowupQuestions(questionId) >= maxFollowups) {
            throw new IllegalArgumentException("每道题最多追问 " + maxFollowups + " 次，已达到上限");
        }

        long sequenceNo = nextSequence(questionId);

        insertMessage(questionId, sequenceNo++, "user", "followup", "followup_question",
                step, null, question.trim(), null, null, null, null,
                requestExtra(question, "followup", step));
        SavedMessage answer = insertMessage(questionId, sequenceNo, "assistant", "followup",
                "followup_answer", step, result.modelName(), result.answer(),
                result.reasoning(), result.inputTokens(), result.outputTokens(),
                result.durationMs(), resultExtra(result));

        Integer followupStep = this.jdbcTemplate.queryForObject(
                "SELECT MAX(step_no) FROM geometry_message "
                        + "WHERE question_id = ? AND phase = 'followup' AND step_no IS NOT NULL",
                Integer.class, questionId);
        int maxStep = followupStep == null ? step : followupStep;
        return new FollowupRecord(questionId, step, answer.createdAt(), result.answer(),
                result.modelName(), maxStep);
    }

    /**
     * 查询一道题的完整历史。
     *
     * @param questionId 题目 ID
     * @return 题目详情
     */
    @Transactional(readOnly = true)
    public QuestionDetail getQuestionDetail(long questionId) {
        List<QuestionRow> rows = this.jdbcTemplate.query(
                "SELECT " + QUESTION_COLUMNS
                        + " FROM geometry_question q WHERE q.id = ? AND q.user_id = ?",
                QUESTION_ROW_MAPPER, questionId, DEFAULT_USER_ID);
        if (rows.isEmpty()) {
            throw new NoSuchElementException("未找到对应题目");
        }

        Map<Long, List<HistoryMessage>> messages = loadMessages(List.of(questionId));
        return buildDetail(rows.get(0),
                messages.getOrDefault(questionId, List.of()));
    }

    /**
     * 读取题目原图，供追问时重新发送给解题模型。
     *
     * <p>历史列表只读取 {@code has_image} 标记，不加载图片二进制；
     * 只有真正发起追问时才读取原图，避免列表接口传输大量数据。</p>
     *
     * @param questionId 题目 ID
     * @return 原图；题目没有保存图片时返回 {@code null}
     */
    @Transactional(readOnly = true)
    public QuestionImage getQuestionImage(long questionId) {
        List<QuestionImage> images = this.jdbcTemplate.query(
                "SELECT image_data, image_content_type, image_filename "
                        + "FROM geometry_question WHERE id = ? AND user_id = ?",
                (rs, rowNum) -> new QuestionImage(
                        rs.getBytes("image_data"),
                        rs.getString("image_content_type"),
                        rs.getString("image_filename")),
                questionId, DEFAULT_USER_ID);

        return images.stream()
                .filter(image -> image.data() != null && image.data().length > 0)
                .findFirst()
                .orElse(null);
    }

    /**
     * 当前每道题允许的追问次数。配置为负数时按 0 处理。
     *
     * @return 最多追问次数
     */
    public int maxFollowupQuestions() {
        return Math.max(0, this.followupProperties.getMaxQuestions());
    }

    /**
     * 按时间、关键词和是否有追问分页查询历史题目。
     *
     * @param from       开始日期，包含当天
     * @param to         结束日期，包含当天
     * @param keyword    题目、讲解或追问关键词
     * @param followup   是否只看有/无追问；为空时不过滤
     * @param page       页码，从 1 开始
     * @param size       每页条数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public HistoryPage listQuestions(LocalDate from, LocalDate to, String keyword,
            Boolean followup, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, size);

        StringBuilder where = new StringBuilder(" WHERE q.user_id = :userId");
        MapSqlParameterSource params = new MapSqlParameterSource("userId", DEFAULT_USER_ID);

        if (from != null) {
            where.append(" AND q.created_at >= :from");
            params.addValue("from", from.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime());
        }
        if (to != null) {
            where.append(" AND q.created_at < :to");
            params.addValue("to", to.plusDays(1).atStartOfDay(ZoneId.systemDefault())
                    .toOffsetDateTime());
        }

        String trimmedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : "";
        if (!trimmedKeyword.isEmpty()) {
            where.append(" AND (q.question_text ILIKE :keyword OR EXISTS ("
                    + "SELECT 1 FROM geometry_message km WHERE km.question_id = q.id "
                    + "AND km.content ILIKE :keyword))");
            params.addValue("keyword", "%" + trimmedKeyword + "%");
        }

        if (followup != null) {
            where.append(followup
                    ? " AND EXISTS (SELECT 1 FROM geometry_message fm "
                            + "WHERE fm.question_id = q.id AND fm.phase = 'followup')"
                    : " AND NOT EXISTS (SELECT 1 FROM geometry_message fm "
                            + "WHERE fm.question_id = q.id AND fm.phase = 'followup')");
        }

        Long totalValue = this.namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geometry_question q" + where, params, Long.class);
        long total = totalValue == null ? 0L : totalValue;

        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);

        List<QuestionRow> rows = this.namedParameterJdbcTemplate.query(
                "SELECT " + QUESTION_COLUMNS + " FROM geometry_question q" + where
                        + " ORDER BY q.created_at DESC, q.id DESC LIMIT :limit OFFSET :offset",
                params, QUESTION_ROW_MAPPER);

        List<Long> ids = rows.stream().map(QuestionRow::id).toList();
        Map<Long, List<HistoryMessage>> messages = loadMessages(ids);
        List<QuestionDetail> items = rows.stream()
                .map(row -> buildDetail(row, messages.getOrDefault(row.id(), List.of())))
                .toList();

        return new HistoryPage(items, total, safePage, safeSize);
    }

    /**
     * 读取一道或多道题的消息，并按题目分组。
     */
    private Map<Long, List<HistoryMessage>> loadMessages(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = questionIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<MessageRow> rows = this.jdbcTemplate.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM geometry_message m WHERE m.question_id IN ("
                        + placeholders + ") ORDER BY m.question_id, m.sequence_no",
                MESSAGE_ROW_MAPPER, questionIds.toArray());

        return rows.stream().collect(Collectors.groupingBy(
                MessageRow::questionId,
                LinkedHashMap::new,
                Collectors.mapping(MessageRow::message, Collectors.toList())));
    }

    /**
     * 把题目行和消息列表组装成接口返回结构。
     */
    private QuestionDetail buildDetail(QuestionRow row, List<HistoryMessage> messages) {
        int maxSolvedPart = messages.stream()
                .filter(message -> "solution_result".equals(message.messageType()))
                .map(HistoryMessage::stepNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);

        List<SolutionPart> solutionParts = messages.stream()
                .filter(message -> "solution_result".equals(message.messageType()))
                .map(message -> new SolutionPart(
                        message.stepNo() == null ? 0 : message.stepNo(),
                        message.content(), message.modelName(), message.createdAt()))
                .toList();

        List<FollowupMessage> followups = messages.stream()
                .filter(message -> "followup".equals(message.phase()))
                .map(message -> new FollowupMessage(
                        message.id(), message.role(), message.content(), message.stepNo(),
                        message.modelName(), message.createdAt()))
                .toList();

        Integer followupStep = followups.stream()
                .map(FollowupMessage::stepNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
        int followupCount = (int) followups.stream()
                .filter(message -> "user".equals(message.role()))
                .count();

        return new QuestionDetail(row.id(), row.userId(), row.questionText(),
                row.imageContentType(), row.imageFilename(), row.hasImage(),
                row.createdAt(), maxSolvedPart, messages, solutionParts, followups,
                followupStep, followupCount, maxFollowupQuestions());
    }

    /**
     * 新建题目记录，保存原始图片字节。
     */
    private long insertQuestion(MultipartFile file, String questionText) {
        byte[] imageBytes = null;
        String contentType = null;
        String filename = null;

        if (file != null && !file.isEmpty()) {
            try {
                imageBytes = file.getBytes();
            } catch (IOException exception) {
                throw new IllegalStateException("读取上传图片失败，请稍后重试", exception);
            }
            contentType = StringUtils.hasText(file.getContentType())
                    ? file.getContentType()
                    : "application/octet-stream";
            filename = file.getOriginalFilename();
        }

        Long questionId = this.jdbcTemplate.queryForObject(
                "INSERT INTO geometry_question "
                        + "(user_id, image_data, image_content_type, image_filename, question_text) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, DEFAULT_USER_ID, imageBytes, contentType, filename, questionText);
        if (questionId == null) {
            throw new IllegalStateException("保存题目记录失败，请稍后重试");
        }
        return questionId;
    }

    /**
     * 确保匿名用户存在，满足题目表的外键约束。
     */
    private void ensureAnonymousUser() {
        this.jdbcTemplate.update(
                "INSERT INTO geometry_user (id, username, nickname) "
                        + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                DEFAULT_USER_ID, "anonymous", "未登录用户");
    }

    /**
     * 锁住题目行，保证同一道题的 sequence_no 分配不会并发冲突。
     */
    private void lockQuestion(long questionId) {
        List<Long> ids = this.jdbcTemplate.query(
                "SELECT id FROM geometry_question WHERE id = ? AND user_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), questionId, DEFAULT_USER_ID);
        if (ids.isEmpty()) {
            throw new NoSuchElementException("未找到对应题目");
        }
    }

    private long nextSequence(long questionId) {
        Integer maxSequence = this.jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM geometry_message WHERE question_id = ?",
                Integer.class, questionId);
        return (maxSequence == null ? 0 : maxSequence) + 1L;
    }

    /**
     * 统计一道题已经发起的追问次数，只统计用户提出的问题。
     */
    private int countFollowupQuestions(long questionId) {
        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geometry_message "
                        + "WHERE question_id = ? AND phase = 'followup' AND role = 'user'",
                Integer.class, questionId);
        return count == null ? 0 : count;
    }

    private void appendRecognitionMessages(long questionId, String userNote,
            ModelCallResult result, boolean retry) {
        long sequenceNo = nextSequence(questionId);
        insertMessage(questionId, sequenceNo++, "user", "recognize", "recognition_request",
                null, null, recognitionRequestContent(userNote, retry), null, null, null, null,
                requestExtra(userNote, "recognize", null));
        insertMessage(questionId, sequenceNo, "assistant", "recognize", "recognition_result",
                null, result.modelName(), result.answer(), result.reasoning(),
                result.inputTokens(), result.outputTokens(), result.durationMs(),
                resultExtra(result));
    }

    private SavedMessage insertMessage(long questionId, long sequenceNo, String role,
            String phase, String messageType, Integer stepNo, String modelName, String content,
            String reasoning, Integer inputTokens, Integer outputTokens, Long durationMs,
            String extraJson) {
        SavedMessage saved = this.jdbcTemplate.queryForObject(
                "INSERT INTO geometry_message "
                        + "(question_id, sequence_no, role, phase, message_type, step_no, "
                        + "model_name, content, reasoning, input_tokens, output_tokens, "
                        + "duration_ms, extra) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "CAST(? AS jsonb)) RETURNING id, created_at",
                (rs, rowNum) -> new SavedMessage(
                        rs.getLong("id"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                questionId, sequenceNo, role, phase, messageType, stepNo, modelName,
                content, reasoning, inputTokens, outputTokens, durationMs,
                extraJson == null ? "{}" : extraJson);
        if (saved == null) {
            throw new IllegalStateException("保存对话记录失败，请稍后重试");
        }
        return saved;
    }

    private String recognitionRequestContent(String userNote, boolean retry) {
        if (StringUtils.hasText(userNote)) {
            return userNote.trim();
        }
        return retry
                ? "请重新识别图片中的题目，并复述题目。"
                : "请识别图片中的题目，并复述题目。";
    }

    private String solutionRequestContent(String userNote, int part) {
        if (StringUtils.hasText(userNote)) {
            return userNote.trim();
        }
        return part == 1 ? "请开始第 1 段讲解。" : "请继续讲解第 " + part + " 段。";
    }

    private String requestExtra(String userNote, String stage, Integer part) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (StringUtils.hasText(userNote)) {
            extra.put("userNote", userNote.trim());
        }
        extra.put("stage", stage);
        if (part != null) {
            extra.put("part", part);
        }
        return toJson(extra);
    }

    private String resultExtra(ModelCallResult result) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (StringUtils.hasText(result.stopReason())) {
            extra.put("stopReason", result.stopReason());
        }
        return toJson(extra);
    }

    private String toJson(Map<String, Object> extra) {
        try {
            return this.objectMapper.writeValueAsString(extra);
        } catch (JacksonException exception) {
            return "{}";
        }
    }

    private record QuestionRow(long id, long userId, String questionText,
            String imageContentType, String imageFilename, boolean hasImage,
            OffsetDateTime createdAt) {
    }

    private record MessageRow(long questionId, HistoryMessage message) {
    }

    private record SavedMessage(long id, OffsetDateTime createdAt) {
    }

    /**
     * 保存追问后的结果，供接口层组装响应。
     *
     * @param questionId   题目 ID
     * @param step         本次追问关联的段落
     * @param createdAt    回复创建时间
     * @param answer       模型回复
     * @param modelName    模型名称
     * @param followupStep 该题所有追问中最大的段落
     */
    public record FollowupRecord(long questionId, int step, OffsetDateTime createdAt,
            String answer, String modelName, int followupStep) {
    }
}
