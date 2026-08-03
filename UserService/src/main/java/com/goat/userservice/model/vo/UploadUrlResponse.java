package com.goat.userservice.model.vo;

import lombok.Data;


@Data
public class UploadUrlResponse {
    // 上传文件的地址
    public String uploadUrl;

    // 下载文件的地址
    public String downloadUrl;
}