package com.goat.userservice.service;

import com.goat.common.exception.BusinessException;
import com.goat.userservice.mapper.UserSessionMapper;
import com.goat.userservice.model.dto.response.ChatFileDownloadResponse;
import com.goat.userservice.model.dto.response.ChatFileUploadResponse;
import com.goat.userservice.utils.OssUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatFileServiceTest {
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final OssUtils ossUtils = mock(OssUtils.class);
    private final ChatFileService service = new ChatFileService(userSessionMapper, ossUtils);

    @Test
    void uploadUsesStableObjectInsideTheSession() {
        when(userSessionMapper.selectCount(any())).thenReturn(1L);
        when(ossUtils.uploadUrl(anyString(), anyString(), anyInt())).thenReturn("signed-upload");

        ChatFileUploadResponse response = service.createUploadUrl(101L, 9001L, "photo.jpeg", null);

        assertEquals("signed-upload", response.getUploadUrl());
        assertTrue(response.getObjectName().startsWith("chat/9001/101/"));
        assertTrue(response.getObjectName().endsWith(".jpg"));
        ArgumentCaptor<String> objectName = ArgumentCaptor.forClass(String.class);
        verify(ossUtils).uploadUrl(anyString(), objectName.capture(), anyInt());
        assertEquals(response.getObjectName(), objectName.getValue());
    }

    @Test
    void uploadRetryReusesTheExistingObjectName() {
        when(userSessionMapper.selectCount(any())).thenReturn(1L);
        when(ossUtils.uploadUrl(anyString(), anyString(), anyInt())).thenReturn("signed-upload-2");

        ChatFileUploadResponse response = service.createUploadUrl(
                101L,
                9001L,
                "photo.png",
                "chat/9001/101/existing.png"
        );

        assertEquals("chat/9001/101/existing.png", response.getObjectName());
    }

    @Test
    void downloadRejectsAnObjectFromAnotherSession() {
        when(userSessionMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class,
                () -> service.createDownloadUrl(101L, 9001L, "chat/9002/private.png"));

        verify(ossUtils, never()).temporaryDownloadUrl(anyString(), anyString(), anyInt());
    }

    @Test
    void uploadRetryCannotOverwriteAnotherMembersObject() {
        when(userSessionMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.createUploadUrl(
                101L,
                9001L,
                "photo.png",
                "chat/9001/202/existing.png"
        ));

        verify(ossUtils, never()).uploadUrl(anyString(), anyString(), anyInt());
    }

    @Test
    void downloadReturnsShortLivedUrlForExistingObject() {
        when(userSessionMapper.selectCount(any())).thenReturn(1L);
        when(ossUtils.objectExists(anyString(), anyString())).thenReturn(true);
        when(ossUtils.temporaryDownloadUrl(anyString(), anyString(), anyInt()))
                .thenReturn("signed-download");

        ChatFileDownloadResponse response = service.createDownloadUrl(
                101L,
                9001L,
                "chat/9001/101/existing.webp"
        );

        assertEquals("signed-download", response.getDownloadUrl());
        assertEquals("chat/9001/101/existing.webp", response.getObjectName());
    }
}
