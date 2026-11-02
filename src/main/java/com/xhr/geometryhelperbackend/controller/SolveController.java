package com.xhr.geometryhelperbackend.controller;

import java.util.Map;
import java.util.stream.Collectors;

import com.xhr.geometryhelperbackend.dto.QuestionDetail;
import com.xhr.geometryhelperbackend.dto.SolutionPart;
import com.xhr.geometryhelperbackend.dto.SolveResponse;
import com.xhr.geometryhelperbackend.service.ImageSolveService;
import com.xhr.geometryhelperbackend.service.ModelCallResult;
import com.xhr.geometryhelperbackend.service.QuestionHistoryService;
import com.xhr.geometryhelperbackend.service.StreamTaskRunner;
import com.xhr.geometryhelperbackend.service.UploadedImage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 图片理解与分步解题接口。
 *
 * <p>请求必须是 {@code multipart/form-data}，文件字段名固定为 {@code file}，
 * 可选文字字段为 {@code message}。通过 {@code stage} 控制阶段：</p>
 * <ul>
 *   <li>{@code analyze}（默认）：识别并复述题目；</li>
 *   <li>{@code retry}：用户认为识别有误时重新识别并复述；</li>
 *   <li>{@code confirm}：用户确认题目复述后开始分步讲解，需同时传回 {@code analysis}；</li>
 *   <li>{@code continue}：继续生成下一段讲解，需传回 {@code analysis} 和 {@code part}；
 *   已生成的前文由服务端从数据库读取。</li>
 * </ul>
 *
 * <p>响应使用 SSE：正文通过 {@code delta} 事件实时返回，落库成功后通过
 * {@code commit} 事件返回最终结果和 {@code questionId}。</p>
 */
@RestController
@RequestMapping("/api")
public class SolveController {

    private final ImageSolveService imageSolveService;

    private final QuestionHistoryService questionHistoryService;

    private final StreamTaskRunner streamTaskRunner;

    public SolveController(ImageSolveService imageSolveService,
            QuestionHistoryService questionHistoryService, StreamTaskRunner streamTaskRunner) {
        this.imageSolveService = imageSolveService;
        this.questionHistoryService = questionHistoryService;
        this.streamTaskRunner = streamTaskRunner;
    }

    /**
     * 分阶段处理图片：题目复述 -> 分段讲解。
     *
     * @param file       上传的图片文件；analyze/retry 必填，讲解阶段默认也会发送给解题模型
     * @param message    用户补充说明，可为空
     * @param stage      阶段：analyze、retry、confirm 或 continue
     * @param analysis   用户确认过的题目复述，讲解阶段必填
     * @param part       当前讲解段落，从 1 开始；continue 阶段必填
     * @param history    已弃用，仅为兼容旧前端保留；前文现在由服务端从数据库读取
     * @param questionId 题目历史记录 ID；识别后由服务端返回，讲解阶段回传
     * @return SSE 流
     */
    @PostMapping(value = "/solve", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter solve(@RequestPart("file") MultipartFile file,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "analysis", required = false) String analysis,
            @RequestParam(value = "part", required = false) Integer part,
            @RequestParam(value = "history", required = false) String history,
            @RequestParam(value = "questionId", required = false) Long questionId) {
        // multipart 临时文件会在请求结束后清理，必须在当前线程立即读入内存。
        UploadedImage image = UploadedImage.from(file);
        String phase = (stage == null || stage.isBlank()) ? "analyze" : stage.trim();

        return switch (phase) {
            case "analyze" -> analyze(image, message);
            case "retry" -> retry(image, message, questionId);
            case "confirm" -> solvePart(image, message, analysis, 1, questionId);
            case "continue" -> {
                if (part == null) {
                    throw new IllegalArgumentException("继续讲解时必须提供讲解段落");
                }
                yield solvePart(image, message, analysis, part, questionId);
            }
            default -> throw new IllegalArgumentException("不支持的阶段：" + stage);
        };
    }

    /**
     * 识别题目并创建历史记录。
     */
    private SseEmitter analyze(UploadedImage image, String message) {
        return this.streamTaskRunner.run(session -> {
            int totalParts = this.imageSolveService.getSolveTotalParts();
            session.sendMeta(Map.of("stage", "confirm", "totalParts", totalParts));

            ModelCallResult result = this.imageSolveService.analyzeImage(
                    image, message, session.deltaSink());
            long questionId = this.questionHistoryService.createQuestionWithRecognition(
                    image, message, result);

            session.sendCommit(new SolveResponse("题目复述完成，请核对", "confirm",
                    result.answer(), null, 0, totalParts, false, questionId));
        });
    }

    /**
     * 重新识别题目。如果前端已经拿到题目 ID，则追加到同一道题下面。
     */
    private SseEmitter retry(UploadedImage image, String message, Long questionId) {
        return this.streamTaskRunner.run(session -> {
            int totalParts = this.imageSolveService.getSolveTotalParts();
            session.sendMeta(Map.of("stage", "confirm", "totalParts", totalParts));

            ModelCallResult result = this.imageSolveService.retryAnalyzeImage(
                    image, message, session.deltaSink());
            long savedQuestionId = this.questionHistoryService.saveRecognition(
                    questionId, image, message, result);

            session.sendCommit(new SolveResponse("已重新识别，请再次核对", "confirm",
                    result.answer(), null, 0, totalParts, false, savedQuestionId));
        });
    }

    /**
     * 调用解题引擎生成单段讲解，并补齐前端需要的段落信息。
     */
    private SseEmitter solvePart(UploadedImage image, String message, String analysis,
            int part, Long questionId) {
        return this.streamTaskRunner.run(session -> {
            int totalParts = this.imageSolveService.getSolveTotalParts();
            session.sendMeta(Map.of("stage", "part", "part", part, "totalParts", totalParts));

            // 第 1 段以用户刚确认的题目复述为准；后续段落的题目和前文都从数据库读取，
            // 不再信任客户端回传，避免页面刷新或状态丢失后前后对不上。
            String solveAnalysis = analysis;
            String history = "";
            if (part > 1) {
                if (questionId == null) {
                    throw new IllegalArgumentException("缺少题目 ID，无法继续讲解");
                }
                QuestionDetail detail = this.questionHistoryService.getQuestionDetail(questionId);
                if (part > detail.maxSolvedPart() + 1) {
                    throw new IllegalArgumentException("请先完成前面的讲解段落");
                }
                solveAnalysis = detail.questionText();
                history = detail.solutionParts().stream()
                        .filter(solved -> solved.part() < part)
                        .map(SolutionPart::content)
                        .collect(Collectors.joining("\n\n"));
            }

            // 先调用模型，成功后再写库，失败时不会留下残缺记录。
            ModelCallResult result = this.imageSolveService.solveImage(
                    image, message, solveAnalysis, part, history, session.deltaSink());
            long savedQuestionId = this.questionHistoryService.saveSolutionPart(
                    questionId, image, solveAnalysis, part, message, result);

            boolean done = part >= totalParts;
            String msg = done
                    ? "分步讲解已全部完成，请核对结论"
                    : "已生成新内容，先想一想，再继续";
            session.sendCommit(new SolveResponse(msg, "part", null, result.answer(),
                    part, totalParts, done, savedQuestionId));
        });
    }
}
