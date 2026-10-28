package com.xhr.geometryhelperbackend.controller;

import java.util.stream.Collectors;

import com.xhr.geometryhelperbackend.dto.FollowupAnswerResponse;
import com.xhr.geometryhelperbackend.dto.FollowupRequest;
import com.xhr.geometryhelperbackend.dto.QuestionDetail;
import com.xhr.geometryhelperbackend.service.ImageSolveService;
import com.xhr.geometryhelperbackend.service.ModelCallResult;
import com.xhr.geometryhelperbackend.service.QuestionHistoryService;
import com.xhr.geometryhelperbackend.service.QuestionImage;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题目追问接口。
 *
 * <p>一道题只有一条追问线程，所有追问消息都通过 {@code question_id} 关联到题目。
 * 每条追问消息会记录用户提问时已经展开到的讲解段落。</p>
 */
@RestController
@RequestMapping("/api")
public class FollowupController {

    private final ImageSolveService imageSolveService;

    private final QuestionHistoryService questionHistoryService;

    public FollowupController(ImageSolveService imageSolveService,
            QuestionHistoryService questionHistoryService) {
        this.imageSolveService = imageSolveService;
        this.questionHistoryService = questionHistoryService;
    }

    /**
     * 针对已经生成的分步讲解继续追问。
     *
     * @param request 追问请求
     * @return 模型回复和本次关联的讲解段落
     */
    @PostMapping(value = "/followup", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FollowupAnswerResponse followup(@RequestBody FollowupRequest request) {
        if (request == null || request.questionId() == null) {
            throw new IllegalArgumentException("缺少题目 ID，无法提交追问");
        }
        if (!StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("追问内容不能为空");
        }

        QuestionDetail detail = this.questionHistoryService
                .getQuestionDetail(request.questionId());

        int maxFollowups = detail.maxFollowupQuestions();
        if (maxFollowups == 0) {
            throw new IllegalArgumentException("当前未开启追问功能");
        }
        if (detail.followupCount() >= maxFollowups) {
            throw new IllegalArgumentException("每道题最多追问 " + maxFollowups + " 次，已达到上限");
        }

        int maxSolvedPart = detail.maxSolvedPart();
        if (maxSolvedPart < 1) {
            throw new IllegalArgumentException("请先查看至少一段讲解后再追问");
        }

        int previousStep = detail.followupStep() == null ? 0 : detail.followupStep();
        int requestedStep = request.step() == null ? maxSolvedPart : request.step();
        if (requestedStep < 1) {
            requestedStep = maxSolvedPart;
        }

        // 用户后来看了更后面的段落时，追问线程记录最大段落；但不能超过已生成的段落。
        int step = Math.max(1, Math.min(maxSolvedPart, Math.max(requestedStep, previousStep)));

        String solutionHistory = detail.solutionParts().stream()
                .map(part -> "【第 " + part.part() + " 段】\n" + part.content())
                .collect(Collectors.joining("\n\n"));
        String followupHistory = detail.followups().stream()
                .map(message -> ("assistant".equals(message.role()) ? "老师：" : "用户：")
                        + message.content())
                .collect(Collectors.joining("\n\n"));
        QuestionImage image = this.questionHistoryService
                .getQuestionImage(request.questionId());

        ModelCallResult result = this.imageSolveService.followUp(
                request.message(), detail.questionText(), solutionHistory, followupHistory,
                step, maxSolvedPart, image);

        QuestionHistoryService.FollowupRecord saved = this.questionHistoryService
                .appendFollowup(request.questionId(), step, request.message(), result);

        return new FollowupAnswerResponse("追问已回复", request.questionId(), step,
                result.answer(), result.modelName(), saved.createdAt(), saved.followupStep());
    }
}
