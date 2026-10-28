package com.xhr.geometryhelperbackend.service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.xhr.geometryhelperbackend.config.AiConfig;
import com.xhr.geometryhelperbackend.config.AiProperties;
import com.xhr.geometryhelperbackend.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片识别与正式解题业务服务。
 *
 * <p>职责分为两个阶段：</p>
 * <ol>
 *     <li>识别阶段：把上传的图片转换成 Spring AI 的 {@link Media}，发送给
 *     {@code visionChatClient}，只复述题目内容；</li>
 *     <li>解题阶段：把用户确认后的题目复述、已经输出的讲解段落连同原图发送给
 *     独立的 {@code solveChatClient}，由解题模型像老师一样分步展开；</li>
 *     <li>追问阶段：从数据库读取原图，连同题目复述、已生成讲解和全部历史追问
 *     一起发送给解题模型。</li>
 * </ol>
 *
 * <p>这里选择“一次性读取到内存”的方式，原因是当前上传上限只有 10MB，
 * 且 Spring AI 的 {@code Media} 需要拿到完整字节或 Resource。若后续要支持
 * 更大的图片，建议先压缩/缩放，或者改为对象存储 URL 再传给模型。</p>
 */
@Service
public class ImageSolveService {

    private static final Logger log = LoggerFactory.getLogger(ImageSolveService.class);

    /**
     * 当浏览器/客户端没有提供可靠的 Content-Type 时，根据文件扩展名兜底推断 MIME。
     * 只列出主流且多模态模型普遍支持的图片格式。
     */
    private static final Map<String, String> EXTENSION_TO_MIME_TYPE = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif",
            "bmp", "image/bmp",
            "tif", "image/tiff",
            "tiff", "image/tiff"
    );

    /**
     * 允许上传的图片子类型。BMP、TIFF 会在调用模型前由
     * {@link ImageProcessingService} 转换为 JPEG/PNG。
     */
    private static final Set<String> SUPPORTED_IMAGE_SUBTYPES = Set.of(
            "jpeg", "png", "webp", "gif", "bmp", "tiff");

    /**
     * 用户补充说明的最大字符数，避免超长文本撑大提示词。
     */
    private static final int MAX_USER_NOTE_LENGTH = 500;

    /**
     * 当前分步讲解的总段数，正式讲解和追问都以此作为最大段落。
     */
    private static final int SOLVE_TOTAL_PARTS = 3;

    /**
     * 已输出讲解内容的最大字符数，防止异常请求把提示词无限放大。
     */
    private static final int MAX_SOLVE_CONTEXT_LENGTH = 60_000;

    /**
     * 单次追问的最大字符数。
     */
    private static final int MAX_FOLLOWUP_QUESTION_LENGTH = 2_000;

    /**
     * 追问上下文的长度上限。超出时保留最近的内容，避免提示词无限增长。
     */
    private static final int MAX_FOLLOWUP_CONTEXT_LENGTH = 60_000;

    private final ChatClient visionChatClient;

    private final ChatClient solveChatClient;

    private final AiProperties aiProperties;

    private final ImageProcessingService imageProcessingService;

    public ImageSolveService(
            @Qualifier(AiConfig.VISION_CHAT_CLIENT) ChatClient visionChatClient,
            @Qualifier(AiConfig.SOLVE_CHAT_CLIENT) ChatClient solveChatClient,
            AiProperties aiProperties,
            ImageProcessingService imageProcessingService) {
        this.visionChatClient = visionChatClient;
        this.solveChatClient = solveChatClient;
        this.aiProperties = aiProperties;
        this.imageProcessingService = imageProcessingService;
    }

    /**
     * 第一步：识别并复述题目，供用户核对。不输出解题计划或最终答案。
     *
     * @param file     上传的图片
     * @param userNote 用户补充说明，可为空
     * @return 题目复述及模型调用元数据
     */
    public ModelCallResult analyzeImage(MultipartFile file, String userNote) {
        return callVisionModel(file, buildAnalyzeText(userNote, false), "题目识别");
    }

    /**
     * 用户认为识别有误时，重新识别并复述题目。不输出解题计划或最终答案。
     *
     * @param file     上传的图片
     * @param userNote 用户补充说明，可为空
     * @return 重新识别后的题目复述及模型调用元数据
     */
    public ModelCallResult retryAnalyzeImage(MultipartFile file, String userNote) {
        return callVisionModel(file, buildAnalyzeText(userNote, true), "重新识别");
    }

    /**
     * 第二步：用户确认题目复述后，生成指定段落的分步讲解。
     *
     * <p>每次只让模型生成一段，并把题目复述、用户补充说明以及已经生成的
     * 前文一起送入解题引擎。这样后续追问也可以复用同一份上下文。</p>
     *
     * <p>默认会把原图一并发送给解题模型，方便模型重新核对图形信息；
     * 如果解题模型只支持文本，可在配置中设置
     * {@code app.ai.solve.include-image=false} 关闭图片。</p>
     *
     * @param file     上传的图片，可为空
     * @param userNote 用户补充说明，可为空
     * @param analysis 用户已确认的题目复述
     * @param part     当前要生成的段落，从 1 开始
     * @param history  已经生成的讲解内容，可为空
     * @return 当前段落的讲解内容及模型调用元数据
     */
    public ModelCallResult solveImage(MultipartFile file, String userNote, String analysis,
            int part, String history) {
        if (!StringUtils.hasText(analysis)) {
            throw new IllegalArgumentException("请先确认题目复述，再开始解题");
        }
        if (part < 1 || part > SOLVE_TOTAL_PARTS) {
            throw new IllegalArgumentException("讲解段落必须在 1 到 " + SOLVE_TOTAL_PARTS + " 之间");
        }

        MultipartFile solveFile = this.aiProperties.getSolve().isIncludeImage() ? file : null;
        return callChatModel(this.solveChatClient, solveFile,
                buildSolveText(userNote, analysis, part, history),
                "分步解题第" + part + "段", this.aiProperties.getSolve().getModel(), false);
    }

    /**
     * 基于题目复述、已经生成的分步讲解和之前的追问记录，继续回答用户的问题。
     *
     * <p>追问复用正式解题引擎，并从数据库重新读取题目原图一并发送；同时带上
     * 题目复述、已经生成的分步讲解和之前的追问记录，保证多轮问答上下文完整。</p>
     *
     * @param question        用户本次追问
     * @param analysis        题目复述
     * @param solutionHistory 已生成的分步讲解
     * @param followupHistory 之前的追问记录
     * @param step            用户提问时已展开到的段落
     * @param maxSolvedPart   已生成的最大段落
     * @param image           题目原图，可为空
     * @return 追问回复及模型调用元数据
     */
    public ModelCallResult followUp(String question, String analysis, String solutionHistory,
            String followupHistory, int step, int maxSolvedPart, QuestionImage image) {
        if (!StringUtils.hasText(analysis)) {
            throw new IllegalArgumentException("题目复述为空，暂时无法追问");
        }

        String userQuestion = normalizeFollowUpQuestion(question);
        int safeMaxSolvedPart = Math.max(1, Math.min(maxSolvedPart, SOLVE_TOTAL_PARTS));
        int safeStep = Math.max(1, Math.min(step, safeMaxSolvedPart));

        ImageInput imageInput = null;
        if (this.aiProperties.getSolve().isIncludeImage()
                && image != null && image.data() != null && image.data().length > 0) {
            imageInput = new ImageInput(image.data(),
                    resolveMimeType(image.contentType(), image.filename()), image.filename());
        }

        String text = buildFollowUpText(userQuestion, analysis, solutionHistory,
                followupHistory, safeStep, safeMaxSolvedPart, imageInput != null);

        return callChatModel(this.solveChatClient, imageInput, text, "追问",
                this.aiProperties.getSolve().getModel(), false);
    }

    /**
     * 当前分步讲解的总段数，供接口层返回给前端。
     *
     * @return 总段数
     */
    public int getSolveTotalParts() {
        return SOLVE_TOTAL_PARTS;
    }

    /**
     * 读取图片并调用视觉理解模型。
     *
     * @param file     上传的图片
     * @param userText 发送给模型的完整用户文本
     * @param operation 当前阶段名称，用于日志
     * @return 模型返回的内容及元数据
     */
    private ModelCallResult callVisionModel(MultipartFile file, String userText, String operation) {
        return callChatModel(this.visionChatClient, file, userText, operation,
                this.aiProperties.getVision().getModel(), true);
    }

    /**
     * 调用指定 ChatClient，可选携带图片。
     *
     * <p>对 Anthropic 原生响应优先直接读取 text block，避免 Spring AI
     * 多 generation（例如 thinking 块在前）时只取第一条 generation 导致正文为空。</p>
     *
     * @param chatClient  模型客户端
     * @param file        图片，可为空
     * @param userText    用户文本
     * @param operation   当前阶段名称，用于日志
     * @param configuredModel 配置中的模型名称
     * @param requireImage 是否强制要求图片
     * @return 模型返回的文本及元数据
     */
    private ModelCallResult callChatModel(ChatClient chatClient, MultipartFile file, String userText,
            String operation, String configuredModel, boolean requireImage) {
        ImageInput imageInput = null;
        if (file != null && !file.isEmpty()) {
            // 1. 解析并校验 MIME 类型。不能只相信客户端传来的 Content-Type，
            //    因此 Content-Type 缺失或不合法时再根据扩展名兜底。
            MimeType mimeType = resolveMimeType(file);

            // 2. 读取原始图片字节。MultipartFile 的临时文件会在请求结束后被清理，
            //    所以必须在请求线程内完成读取。原始字节保留给历史记录使用，
            //    压缩后的字节只发给模型。
            byte[] originalImageBytes;
            try {
                originalImageBytes = file.getBytes();
            } catch (IOException exception) {
                log.error("读取上传图片失败 文件名 {}", file.getOriginalFilename(), exception);
                throw new AiServiceException("读取上传图片失败，请稍后重试", exception);
            }
            imageInput = new ImageInput(originalImageBytes, mimeType, file.getOriginalFilename());
        }

        return callChatModel(chatClient, imageInput, userText, operation, configuredModel,
                requireImage);
    }

    private ModelCallResult callChatModel(ChatClient chatClient, ImageInput imageInput,
            String userText, String operation, String configuredModel, boolean requireImage) {
        boolean hasImage = imageInput != null && imageInput.data() != null
                && imageInput.data().length > 0;
        if (requireImage && !hasImage) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        MimeType mimeType = null;
        byte[] imageBytes = null;
        if (hasImage) {
            mimeType = imageInput.mimeType();
            if (mimeType == null || !"image".equalsIgnoreCase(mimeType.getType())) {
                throw new IllegalArgumentException("只支持图片文件，当前文件类型为：" + mimeType);
            }

            // 3. Bedrock/Claude 限制单张图片小于 5MB。所有引擎在调用模型前
            //    都走同一套处理逻辑，避免视觉阶段、解题阶段或追问各自遗漏。
            ImageProcessingService.ProcessedImage processedImage = this.imageProcessingService
                    .process(imageInput.data(), mimeType, imageInput.filename());
            imageBytes = processedImage.bytes();
            mimeType = processedImage.mimeType();
        }

        // 4. 构造 Spring AI 多媒体对象。这里传入的是压缩后的字节和对应 MIME，
        //    Media 内部会再做 base64 编码，因此不能提前把图片转成 base64 字符串。
        Media imageMedia = hasImage
                ? Media.builder().mimeType(mimeType).data(imageBytes).build()
                : null;

        log.info("{} 开始调用模型 带图片={}", operation, hasImage);
        long startNanos = System.nanoTime();
        try {
            // 5. 发送“系统提示词 + 用户文本 + 可选图片”。
            ChatResponse response = chatClient.prompt()
                    .user(userSpec -> {
                        userSpec.text(userText);
                        if (imageMedia != null) {
                            userSpec.media(imageMedia);
                        }
                    })
                    .call()
                    .chatResponse();

            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            ModelCallResult result = extractModelResult(operation, response,
                    configuredModel, costMillis);
            if (!StringUtils.hasText(result.answer())) {
                throw new AiServiceException("大模型未返回有效内容，请稍后重试");
            }

            log.info("{} 模型调用完成 带图片={} 发送图片={}KB 耗时={}s 输入Token={} 输出Token={}",
                    operation, hasImage,
                    imageBytes == null ? 0 : imageBytes.length / 1024,
                    costMillis / 1000, result.inputTokens(), result.outputTokens());
            return result;
        } catch (AiServiceException exception) {
            // 保留业务异常的原始信息，避免被下面的通用捕获再次包装。
            throw exception;
        } catch (Exception exception) {
            long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("{} 模型调用失败 带图片={} 耗时={}s",
                    operation, hasImage, costMillis / 1000, exception);
            throw new AiServiceException("大模型服务调用失败，请检查 API Key、模型名称或网络后重试", exception);
        }
    }

    /**
     * 从响应中提取正文和可入库的元数据。Anthropic 原生响应直接读取
     * text/thinking block；其他协议回退到最后一个非空 generation，
     * 并尽量从 Spring AI 元数据中读取 Token 用量。
     */
    private ModelCallResult extractModelResult(String operation, ChatResponse response,
            String configuredModel, long durationMs) {
        if (response == null) {
            return new ModelCallResult("", configuredModel, null, null, durationMs, null, null);
        }

        Object rawResponse = response.getMetadata() == null
                ? null
                : response.getMetadata().get("anthropic-response");
        if (rawResponse instanceof Message message) {
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            for (ContentBlock block : message.content()) {
                if (block.isText()) {
                    text.append(block.asText().text());
                } else if (block.isThinking()) {
                    reasoning.append(block.asThinking().thinking());
                }
            }

            String stopReason = message.stopReason().map(Object::toString).orElse(null);
            Integer inputTokens = (int) message.usage().inputTokens();
            Integer outputTokens = (int) message.usage().outputTokens();
            if (!StringUtils.hasText(text)) {
                log.warn("{} Anthropic 响应没有有效 text block，stopReason={} contentBlockCount={}",
                        operation, stopReason, message.content().size());
            }

            return new ModelCallResult(text.toString().trim(), configuredModel,
                    inputTokens, outputTokens, durationMs,
                    StringUtils.hasText(reasoning) ? reasoning.toString().trim() : null,
                    stopReason);
        }

        String answer = "";
        List<Generation> results = response.getResults();
        for (int index = results.size() - 1; index >= 0; index -= 1) {
            Generation generation = results.get(index);
            if (generation != null && generation.getOutput() != null
                    && StringUtils.hasText(generation.getOutput().getText())) {
                answer = generation.getOutput().getText();
                break;
            }
        }

        String modelName = configuredModel;
        Integer inputTokens = null;
        Integer outputTokens = null;
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            Usage usage = metadata.getUsage();
            if (usage != null) {
                inputTokens = usage.getPromptTokens();
                outputTokens = usage.getCompletionTokens();
            }
            if (StringUtils.hasText(metadata.getModel())) {
                modelName = metadata.getModel();
            }
        }

        return new ModelCallResult(answer.trim(), modelName, inputTokens, outputTokens,
                durationMs, null, null);
    }

    /**
     * 组装视觉阶段的系统提示词和用户补充说明。用户输入永远追加在系统组织的内容之后。
     */
    private String buildBaseText(String userNote) {
        String basePrompt = this.aiProperties.getVision().getUserPrompt();
        String note = normalizeUserNote(userNote);
        return note.isEmpty() ? basePrompt : basePrompt + "\n\n用户补充说明：\n" + note;
    }

    /**
     * 去除补充说明首尾空白并校验长度。两个阶段共用同一套限制。
     */
    private String normalizeUserNote(String userNote) {
        if (!StringUtils.hasText(userNote)) {
            return "";
        }
        String note = userNote.trim();
        if (note.length() > MAX_USER_NOTE_LENGTH) {
            throw new IllegalArgumentException("补充说明不能超过 " + MAX_USER_NOTE_LENGTH + " 个字");
        }
        return note;
    }

    /**
     * 构造题目识别阶段的用户文本。该阶段只复述题目，不输出解题计划。
     */
    private String buildAnalyzeText(String userNote, boolean retry) {
        String intro = retry
                ? """
                        用户认为上一次的题目识别不准确。请重新独立阅读图片，并结合用户补充说明，重新复述题目。
                        不要沿用上一次的错误理解。如果仍然看不清，请只在复述中注明无法辨认的信息，不要猜测。
                        """
                : """
                        现在开始新的任务：题目复述。本段要求优先于前面的图片描述要求。
                        请仔细阅读图片和用户补充说明，只复述题目，不要开始解题，不要输出解题计划，
                        不要询问用户是否确认，也不要输出“需要确认”之类的部分。
                        """;
        return buildBaseText(userNote) + "\n\n" + intro + """

                请严格按以下结构输出：
                ## 题目复述
                写出已知条件、要求解或证明的目标，以及图中关键信息。
                只复述题目本身，不要分析解法，不要给出最终答案。

                数学公式必须使用 LaTeX，并且能被 KaTeX 正常渲染：
                - 行内公式用 $...$，例如 $AB=AC$；
                - 独立公式用 $$...$$，例如 $$S=\\frac{1}{2}ah$$；
                - 几何符号使用标准命令，如 \\angle、\\triangle、\\frac、\\sqrt、\\boxed；
                - 不要用 Unicode 上下标或纯文本替代公式。
                """;
    }

    /**
     * 构造分步讲解阶段的用户文本。
     *
     * <p>每次只要求模型输出当前段落，并把已经输出的段落作为上下文一起发送，
     * 既避免一次把答案全部倒给学生，也为后续追问保留完整的对话前文。</p>
     */
    private String buildSolveText(String userNote, String analysis, int part, String history) {
        String note = normalizeUserNote(userNote);
        String noteBlock = note.isEmpty() ? "" : "用户补充说明：\n" + note + "\n\n";
        String historyBlock = normalizeSolveHistory(history);
        String knowledgeScope = normalizeKnowledgeScope(
                this.aiProperties.getSolve().getKnowledgeScope());
        String stageInstruction = switch (part) {
            case 1 -> """
                    这一轮只输出第 1 段：先点明题目中的关键条件和目标，再用一两句提示引导学生找到切入点；
                    然后只完成起步的一步（例如确定思路、作辅助线或列出第一个关系式），
                    停在下一段会继续讲的位置。不要向用户提问，不要要求学生回答、写出来或告诉任何人；
                    不要提前输出后续完整推导，不要给出最终答案。
                    """;
            case 2 -> """
                    这一轮只输出第 2 段：承接前文和学生已经思考过的内容，继续完成中间的核心推导。
                    拆成两到四个清晰的小步骤，每一步写清楚依据、公式和计算；
                    在关键处用一句简短提示点出思考方向，但不要向用户提问，也不要要求用户回答或写出结果；
                    不要提前给出最终答案。
                    """;
            default -> """
                    这一轮只输出第 3 段：承接前文完成剩余的推导，最后给出明确的结论或答案，
                    并用一两句话回顾关键思路或检验结果。不要重复前两段已经讲过的内容。
                    """;
        };

        return noteBlock
                + "用户已确认以下题目复述，这是本次解题的唯一题目依据：\n"
                + analysis.trim()
                + "\n\n"
                + (historyBlock.isEmpty() ? "" : "本次讲解已经输出的前文如下，必须承接它，不要重复：\n"
                        + historyBlock + "\n\n")
                + "现在开始讲解第 " + part + " / " + SOLVE_TOTAL_PARTS + " 段。\n"
                + stageInstruction
                + """

                通用要求：
                1. 本段的分步要求优先于系统提示词中“给出完整解题过程或结论”的要求，
                   非最后一段不要提前给出最终答案，也不要一次把整道题讲完；
                2. 你是一位循循善诱的老师，先提示思考方向，再逐步揭开答案；
                3. 这是单向分页讲解，用户当前不能随时追问。不要出现“写出来告诉我”“你来说说”
                   “回答我”“试试后告诉我”等邀请用户回复的话术，也不要等待用户回复；
                   需要留思考空间时，用“先想一想”即可，下一段会继续讲解；
                4. 讲解必须符合当前允许的知识范围，不得使用超纲定理、超纲公式或超纲方法；
                5. 每一步写清楚依据、公式、代入和计算，不得跳步，不使用“易知”“易得”“显然”等省略性表述；
                6. 如果发现用户确认的题目复述有明显错误，请先简短指出，再按正确理解继续；
                7. 不要输出“第 x / 3 段”之类的段落标题，直接输出本段讲解内容；
                8. 使用简体中文和清晰的 Markdown 格式输出；
                9. 数学公式必须使用 LaTeX，并且能被 KaTeX 正常渲染：
                   行内公式用 $...$，独立公式用 $$...$$；
                   几何符号使用标准命令，如 \\angle、\\triangle、\\frac、\\sqrt、\\boxed；
                   不要用 Unicode 上下标或纯文本替代公式。

                当前允许使用的知识范围：只允许使用「%s」范围内的知识。
                超出这个范围的内容不要使用；如果题目确实无法在该范围内解决，
                请明确说明缺少哪一类知识，不要改用超纲方法硬解。
                """.formatted(knowledgeScope);
    }

    /**
     * 构造追问阶段的用户文本。
     *
     * <p>追问不是继续生成下一段，而是围绕用户当前看到的内容做解释。因此提示词
     * 会明确限制模型不要重复整段讲解、不要主动剧透后续段落，并带上此前追问，
     * 让多轮问答保持连贯。</p>
     */
    private String buildFollowUpText(String question, String analysis, String solutionHistory,
            String followupHistory, int step, int maxSolvedPart, boolean hasImage) {
        String knowledgeScope = normalizeKnowledgeScope(
                this.aiProperties.getSolve().getKnowledgeScope());
        String solutionBlock = normalizeFollowUpContext(solutionHistory);
        String historyBlock = normalizeFollowUpContext(followupHistory);
        String imageNote = hasImage
                ? "本题原图已随本消息附上，请结合图片中的图形、标注和条件理解题目。\n\n"
                : "";

        return imageNote + """
                用户已确认的题目复述：
                %s

                已经生成的分步讲解（目前生成到第 %d 段）：
                %s

                之前的追问记录（按时间顺序，包含用户问题和老师回答）：
                %s

                用户在看到第 %d 段讲解后提出新的追问：
                %s

                现在是追问环节，不是继续生成下一段。请结合题目原图（如有）、题目复述、
                已经生成的讲解和上面的历史追问一起回答，不要只根据最后一句话作答：
                1. 不要重复整段讲解，不要重新从头解题；
                2. 如果用户问某一步的依据、计算或辅助线，请针对该步解释清楚，必要时补充一个小例子或反推；
                3. 用户当前只看到第 %d 段，优先解释这一段及之前的内容；不要主动提前给出后续段落或最终答案，除非用户明确要求；
                4. 不要向用户提问，不要要求用户“回答我”“写出来”或等待用户回复；
                5. 使用简体中文和清晰的 Markdown；数学公式必须使用 LaTeX，行内公式用 $...$，独立公式用 $$...$$；
                6. 只允许使用「%s」范围内的知识，不得使用超纲定理或超纲方法；
                7. 如果用户的问题与当前题目无关，先简短指出，再尽量回到题目本身。
                """.formatted(
                analysis.trim(),
                maxSolvedPart,
                solutionBlock,
                historyBlock,
                step,
                question,
                step,
                knowledgeScope);
    }

    /**
     * 校验并规范化用户追问。
     */
    private String normalizeFollowUpQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("追问内容不能为空");
        }
        String value = question.trim();
        if (value.length() > MAX_FOLLOWUP_QUESTION_LENGTH) {
            throw new IllegalArgumentException(
                    "追问内容不能超过 " + MAX_FOLLOWUP_QUESTION_LENGTH + " 个字");
        }
        return value;
    }

    /**
     * 追问上下文超长时保留最近的内容，而不是直接拒绝用户。
     */
    private String normalizeFollowUpContext(String context) {
        if (!StringUtils.hasText(context)) {
            return "（暂无）";
        }
        String value = context.trim();
        if (value.length() > MAX_FOLLOWUP_CONTEXT_LENGTH) {
            return "（较早内容已省略）\n"
                    + value.substring(value.length() - MAX_FOLLOWUP_CONTEXT_LENGTH);
        }
        return value;
    }

    /**
     * 规范化已输出的讲解前文，并限制长度。
     */
    private String normalizeSolveHistory(String history) {
        if (!StringUtils.hasText(history)) {
            return "";
        }
        String value = history.trim();
        if (value.length() > MAX_SOLVE_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("已输出的讲解内容过长，请重新开始本次解题");
        }
        return value;
    }

    /**
     * 规范化配置中的知识范围。配置为空时退回默认的初二及以前各学科范围。
     */
    private String normalizeKnowledgeScope(String knowledgeScope) {
        return StringUtils.hasText(knowledgeScope)
                ? knowledgeScope.trim()
                : "中国国内初二及以前各学科知识（不限于数学）";
    }

    /**
     * 解析上传文件的 MIME 类型。
     *
     * <p>优先使用客户端提供的 Content-Type；如果缺失、格式错误或不是 image/*，
     * 再根据文件扩展名兜底。两者都无法识别时直接抛出参数异常。</p>
     *
     * @param file 上传文件
     * @return 图片 MIME 类型
     */
    private MimeType resolveMimeType(MultipartFile file) {
        return resolveMimeType(file.getContentType(), file.getOriginalFilename());
    }

    /**
     * 根据 MIME 类型和文件名推断图片格式。追问使用的是数据库中保存的原图，
     * 其 MIME 和文件名可能为空，因此与上传文件共用同一套兜底逻辑。
     */
    private MimeType resolveMimeType(String contentType, String filename) {
        if (StringUtils.hasText(contentType)) {
            try {
                MimeType parsed = MimeType.valueOf(contentType);
                if ("image".equalsIgnoreCase(parsed.getType())) {
                    return normalizeImageMimeType(parsed);
                }
            } catch (IllegalArgumentException ignored) {
                // Content-Type 格式不合法时继续尝试扩展名兜底。
            }
        }

        String extension = StringUtils.getFilenameExtension(filename);
        if (StringUtils.hasText(extension)) {
            String mimeType = EXTENSION_TO_MIME_TYPE.get(extension.toLowerCase(Locale.ROOT));
            if (mimeType != null) {
                return MimeType.valueOf(mimeType);
            }
        }

        throw new IllegalArgumentException(
                "无法识别图片格式，仅支持 jpg、jpeg、png、webp、gif、bmp、tiff");
    }

    /**
     * 规范化图片 MIME 类型，并拦截模型普遍不支持的格式。
     *
     * <p>部分客户端会发送 {@code image/jpg} 或 {@code image/pjpeg}，
     * 它们都不是标准 MIME，统一转换成 {@code image/jpeg}，
     * 避免 OpenAI 兼容接口因格式不合法而拒绝请求。</p>
     *
     * @param mimeType 原始 MIME 类型
     * @return 规范化后的 MIME 类型
     */
    private MimeType normalizeImageMimeType(MimeType mimeType) {
        String subtype = mimeType.getSubtype().toLowerCase(Locale.ROOT);
        if ("jpg".equals(subtype) || "pjpeg".equals(subtype) || "x-jpg".equals(subtype)) {
            return MimeType.valueOf("image/jpeg");
        }
        if ("tif".equals(subtype) || "x-tiff".equals(subtype)) {
            subtype = "tiff";
        }
        if ("x-ms-bmp".equals(subtype) || "x-bmp".equals(subtype)) {
            subtype = "bmp";
        }
        if ("x-png".equals(subtype)) {
            subtype = "png";
        }
        if ("x-webp".equals(subtype)) {
            subtype = "webp";
        }
        if (!SUPPORTED_IMAGE_SUBTYPES.contains(subtype)) {
            throw new IllegalArgumentException(
                    "仅支持 jpg、jpeg、png、webp、gif、bmp、tiff 格式的图片");
        }
        return MimeType.valueOf("image/" + subtype);
    }

    /**
     * 一次模型调用中携带的原始图片。上传文件和数据库原图都会转换成这个结构。
     */
    private record ImageInput(byte[] data, MimeType mimeType, String filename) {
    }
}
