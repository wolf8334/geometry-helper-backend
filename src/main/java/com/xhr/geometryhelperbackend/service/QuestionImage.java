package com.xhr.geometryhelperbackend.service;

/**
 * 从数据库读取的题目原图。
 *
 * <p>追问时需要把原图一并发送给解题模型。这里直接携带原始字节，
 * 由 {@link ImageProcessingService} 在调用模型前完成必要的压缩和格式转换。</p>
 *
 * @param data        原始图片字节
 * @param contentType 上传时记录的 MIME 类型
 * @param filename    原始文件名
 */
public record QuestionImage(byte[] data, String contentType, String filename) {
}
