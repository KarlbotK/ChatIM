package com.goat.userservice.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 会话表
 * @TableName session
 */
@TableName(value ="session")
@Data
public class Session implements Serializable {
    /**
     * 会话 ID
     */
    @TableId
    private Long sessionId;

    /**
     * 名称
     */
    private String name;

    /**
     * 类别：0 单聊，1 群聊
     */
    private Integer type;

    /**
     * 状态：0 正常，1 删除
     */
    private Integer status;

    /**
     * 创建时间
     */
    private Date createdTime;

    /**
     * 更新时间
     */
    private Date updatedTime;

    /**
     * 群聊头像
     */
    private String avatar;

    /**
     * 群聊头像在对象存储中的稳定对象名。
     */
    private String avatarObjectName;

    /**
     * 群公告，仅群聊使用。
     */
    private String announcement;

    @TableField(exist = false)
    @Serial
    private static final long serialVersionUID = 1L;
}
