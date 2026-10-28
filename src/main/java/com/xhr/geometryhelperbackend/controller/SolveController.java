package com.xhr.geometryhelperbackend.controller;

import com.xhr.geometryhelperbackend.dto.SolveResponse;
import com.xhr.geometryhelperbackend.service.ImageSolveService;
import com.xhr.geometryhelperbackend.service.ModelCallResult;
import com.xhr.geometryhelperbackend.service.QuestionHistoryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片理解与分步解题接口。
 *
 * <p>请求必须是 {@code multipart/form-data}，文件字段名固定为 {@code file}，
 * 可选文字字段为 {@code message}。通过 {@code stage} 控制阶段：</p>
 * <ul>
 *   <li>{@code analyze}（默认）：识别并复述题目；</li>
 *   <li>{@code retry}：用户认为识别有误时重新识别并复述；</li>
 *   <li>{@code confirm}：用户确认题目复述后开始分步讲解，需同时传回 {@code analysis}；</li>
 *   <li>{@code continue}：继续生成下一段讲解，需传回 {@code analysis}、{@code part}
 *   和已经生成的 {@code history}。</li>
 * </ul>
 *
 * <p>识别阶段结束后会返回 {@code questionId}，后续阶段和追问接口需要带回该 ID。</p>
 */
@RestController
@RequestMapping("/api")
public class SolveController {

    private final ImageSolveService imageSolveService;

    private final QuestionHistoryService questionHistoryService;

    public SolveController(ImageSolveService imageSolveService,
            QuestionHistoryService questionHistoryService) {
        this.imageSolveService = imageSolveService;
        this.questionHistoryService = questionHistoryService;
    }

    /**
     * 分阶段处理图片：题目复述 -> 分段讲解。
     *
     * @param file       上传的图片文件；analyze/retry 必填，讲解阶段默认也会发送给解题模型
     * @param message    用户补充说明，可为空
     * @param stage      阶段：analyze、retry、confirm 或 continue
     * @param analysis   用户确认过的题目复述，讲解阶段必填
     * @param part       当前讲解段落，从 1 开始；continue 阶段必填
     * @param history    已经生成的讲解内容，continue 阶段用于携带上下文
     * @param questionId 题目历史记录 ID；识别后由服务端返回，讲解阶段回传
     * @return 当前阶段的结果
     */
    @PostMapping(value = "/solve", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SolveResponse solve(@RequestPart("file") MultipartFile file,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "analysis", required = false) String analysis,
            @RequestParam(value = "part", required = false) Integer part,
            @RequestParam(value = "history", required = false) String history,
            @RequestParam(value = "questionId", required = false) Long questionId) {
        String phase = (stage == null || stage.isBlank()) ? "analyze" : stage.trim();

        return switch (phase) {
            case "analyze" -> analyze(file, message);
            case "retry" -> retry(file, message, questionId);
            case "confirm" -> solvePart(file, message, analysis, 1, history, questionId);
            case "continue" -> {
                if (part == null) {
                    throw new IllegalArgumentException("继续讲解时必须提供讲解段落");
                }
                yield solvePart(file, message, analysis, part, history, questionId);
            }
            default -> throw new IllegalArgumentException("不支持的阶段：" + stage);
        };
    }

    /**
     * 识别题目并创建历史记录。
     */
    private SolveResponse analyze(MultipartFile file, String message) {
        ModelCallResult result = this.imageSolveService.analyzeImage(file, message);
        long questionId = this.questionHistoryService.createQuestionWithRecognition(
                file, message, result);
        return confirmResponse("题目复述完成，请核对", result.answer(), questionId);
    }

    /**
     * 重新识别题目。如果前端已经拿到题目 ID，则追加到同一道题下面。
     */
    private SolveResponse retry(MultipartFile file, String message, Long questionId) {
        ModelCallResult result = this.imageSolveService.retryAnalyzeImage(file, message);
        long savedQuestionId = this.questionHistoryService.saveRecognition(
                questionId, file, message, result);
        return confirmResponse("已重新识别，请再次核对", result.answer(), savedQuestionId);
    }

    /**
     * 调用解题引擎生成单段讲解，并补齐前端需要的段落信息。
     */
    private SolveResponse solvePart(MultipartFile file, String message, String analysis,
            int part, String history, Long questionId) {
        int totalParts = this.imageSolveService.getSolveTotalParts();
        long savedQuestionId = this.questionHistoryService.ensureQuestion(
                questionId, file, analysis);
        ModelCallResult result = this.imageSolveService.solveImage(
                file, message, analysis, part, history);
        this.questionHistoryService.appendSolutionPart(savedQuestionId, part, message, result);

        boolean done = part >= totalParts;
        String msg = done
                ? "分步讲解已全部完成，请核对结论"
                : "已生成新内容，先想一想，再继续";
        return new SolveResponse(msg, "part", null, result.answer(), part, totalParts, done,
                savedQuestionId);
    }

    /**
     * 题目复述阶段的统一响应。
     */
    private SolveResponse confirmResponse(String message, String analysis, long questionId) {
        return new SolveResponse(message, "confirm", analysis, null,
                0, this.imageSolveService.getSolveTotalParts(), false, questionId);
    }
}
