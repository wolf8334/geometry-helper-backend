package com.xhr.geometryhelperbackend.controller;

import com.xhr.geometryhelperbackend.dto.SolveResponse;
import com.xhr.geometryhelperbackend.service.ImageSolveService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片理解接口。
 */
@RestController
@RequestMapping("/api")
public class SolveController {

    private final ImageSolveService imageSolveService;

    public SolveController(ImageSolveService imageSolveService) {
        this.imageSolveService = imageSolveService;
    }

    /**
     * 上传图片并返回大模型生成的图片内容描述。
     *
     * <p>请求必须是 {@code multipart/form-data}，文件字段名固定为 {@code file}。</p>
     *
     * <p>示例：</p>
     * <pre>
     * curl -X POST <a href="http://localhost:8080/api/solve">...</a> -F "file=@D:/test.png"
     * </pre>
     *
     * <p>示例响应：</p>
     * <pre>
     * {
     *   "msg": "图片上传成功",
     *   "answer": "图片中是一个由三条边组成的三角形……"
     * }
     * </pre>
     *
     * @param file 上传的图片文件
     * @return 上传结果和模型描述
     */
    @PostMapping(value = "/solve",consumes = MediaType.MULTIPART_FORM_DATA_VALUE,produces = MediaType.APPLICATION_JSON_VALUE)
    public SolveResponse solve(@RequestPart("file") MultipartFile file) {
        String answer = this.imageSolveService.describeImage(file);
        return new SolveResponse("图片上传成功", answer);
    }
}
