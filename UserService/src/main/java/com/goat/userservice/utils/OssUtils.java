package com.goat.userservice.utils;


import cn.hutool.core.util.StrUtil;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class OssUtils {

    @Resource
    private MinioClient minioClient;

    @Value("${minio.url}")
    private String url;

    @SneakyThrows
    public String uploadUrl(String bucketName, String objectName, Integer expires) {

        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(expires, TimeUnit.SECONDS)
                        .build());
    }

    public String downUrl(String bucketName, String fileName) {
        return url + StrUtil.SLASH + bucketName + StrUtil.SLASH + fileName;
    }

    @SneakyThrows
    public boolean objectExists(String bucketName, String objectName) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            return true;
        } catch (io.minio.errors.ErrorResponseException exception) {
            String code = exception.errorResponse() == null ? null : exception.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return false;
            }
            throw exception;
        }
    }
}
