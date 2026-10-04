package com.vibemusic.service.impl;

import com.vibemusic.constant.MessageConstant;
import com.vibemusic.service.MinioService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

@Service
@Slf4j
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${VIBE_MINIO_PUBLIC_URL:${minio.endpoint}}")
    private String publicUrl;

    public MinioServiceImpl(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 上传文件到 Minio
     *
     * @param file   文件
     * @param folder 文件夹
     * @return 可访问的 URL
     */
    @Override
    public String uploadFile(MultipartFile file, String folder) {
        try {
            if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024 ||
                    !("image/jpeg".equals(file.getContentType()) || "image/png".equals(file.getContentType()) || "image/webp".equals(file.getContentType()))) {
                throw new IllegalArgumentException("只能上传不超过 2 MB 的 JPG、PNG 或 WebP 图片");
            }
            // 生成唯一文件名

            String suffix = switch (file.getContentType()) {
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                default -> ".jpg";
            };
            String fileName = folder + "/" + UUID.randomUUID() + suffix;
            log.info("上传文件: {}", fileName);
            // 获取文件流
            InputStream inputStream = file.getInputStream();

            // 上传文件
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );

            // 返回可访问的 URL

            return publicUrl.replaceAll("/+$", "") + "/" + bucketName + "/" + fileName;

        } catch (Exception e) {
            throw new RuntimeException(MessageConstant.FILE_UPLOAD + MessageConstant.FAILED + "：" + e.getMessage());
        }
    }

    /**
     * 删除文件
     *
     * @param fileUrl 文件 URL
     */
    @Override
    public void deleteFile(String fileUrl) {

        try {
            // 解析 URL，获取文件路径
            String path = URI.create(fileUrl).getPath();
            String bucketPath = "/" + bucketName + "/";
            int bucketIndex = path.indexOf(bucketPath);
            if (bucketIndex < 0) {
                throw new IllegalArgumentException("图片地址不属于当前 MinIO Bucket");
            }
            String filePath = path.substring(bucketIndex + bucketPath.length());
            if (filePath.isBlank() || filePath.contains("..")) {
                throw new IllegalArgumentException("无效的图片路径");
            }
            log.info("删除文件: {}", filePath);
            // 删除文件
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(filePath)
                            .build()
            );

        } catch (Exception e) {
            throw new RuntimeException("文件删除失败: " + e.getMessage());
        }
    }
}
