package ai.inquery.server.web.api.controller.aichat;

import ai.inquery.server.domain.api.model.AiChatMessage;
import ai.inquery.server.domain.api.model.AiChatRoom;
import ai.inquery.server.domain.api.param.AiChatMessageCreateParam;
import ai.inquery.server.domain.api.param.AiChatMessageUpdateParam;
import ai.inquery.server.domain.api.param.AiChatRoomCreateParam;
import ai.inquery.server.domain.api.param.AiChatRoomUpdateParam;
import ai.inquery.server.domain.api.service.AiChatMessageService;
import ai.inquery.server.domain.api.service.AiChatRoomService;
import ai.inquery.server.domain.core.attachment.AiChatAttachmentService;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.AiChatAttachmentDO;
import ai.inquery.server.domain.repository.mapper.AiChatMessageAttachmentMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.web.api.controller.ai.attachment.AttachmentMetaDTO;
import ai.inquery.server.web.api.controller.aichat.request.ChatRoomCreateRequest;
import ai.inquery.server.web.api.controller.aichat.request.ChatRoomUpdateRequest;
import ai.inquery.server.web.api.controller.aichat.request.MessageSaveRequest;
import ai.inquery.server.web.api.controller.aichat.request.MessageUpdateRequest;
import ai.inquery.server.web.api.controller.aichat.vo.ChatRoomVO;
import ai.inquery.server.web.api.controller.aichat.vo.MessageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI Chat Room Controller
 *
 */
@RestController
@RequestMapping("/api/ai/chat-room")
@Slf4j
public class AiChatRoomController {

    @Autowired
    private AiChatRoomService aiChatRoomService;

    @Autowired
    private AiChatMessageService aiChatMessageService;

    @Autowired
    private AiChatAttachmentService chatAttachmentService;

    /**
     * Create chat room
     */
    @PostMapping("/create")
    public DataResult<Long> createChatRoom(@RequestBody ChatRoomCreateRequest request) {
        AiChatRoomCreateParam param = new AiChatRoomCreateParam();
        param.setConversationId(request.getConversationId());
        param.setTitle(request.getTitle());
        param.setUserId(request.getUserId());
        return aiChatRoomService.create(param);
    }

    /**
     * Update chat room
     */
    @PostMapping("/update")
    public ActionResult updateChatRoom(@RequestBody ChatRoomUpdateRequest request) {
        AiChatRoomUpdateParam param = new AiChatRoomUpdateParam();
        param.setId(request.getId());
        param.setTitle(request.getTitle());
        return aiChatRoomService.update(param);
    }

    /**
     * Delete chat room
     */
    @DeleteMapping("/delete/{id}")
    public ActionResult deleteChatRoom(@PathVariable Long id) {
        return aiChatRoomService.delete(id);
    }

    /**
     * Get chat room by id
     */
    @GetMapping("/get/{id}")
    public DataResult<ChatRoomVO> getChatRoom(@PathVariable Long id) {
        DataResult<AiChatRoom> result = aiChatRoomService.get(id);
        if (result.success() && result.getData() != null) {
            ChatRoomVO vo = new ChatRoomVO();
            BeanUtils.copyProperties(result.getData(), vo);
            return DataResult.of(vo);
        }
        return DataResult.empty();
    }

    /**
     * Get chat room by conversation id
     */
    @GetMapping("/conversation/{conversationId}")
    public DataResult<ChatRoomVO> getChatRoomByConversationId(@PathVariable String conversationId) {
        DataResult<AiChatRoom> result = aiChatRoomService.getByConversationId(conversationId);
        if (result.success() && result.getData() != null) {
            ChatRoomVO vo = new ChatRoomVO();
            BeanUtils.copyProperties(result.getData(), vo);
            return DataResult.of(vo);
        }
        return DataResult.empty();
    }

    /**
     * List chat rooms by user id
     */
    @GetMapping("/list/{userId}")
    public ListResult<ChatRoomVO> listChatRooms(@PathVariable Long userId) {
        ListResult<AiChatRoom> result = aiChatRoomService.listByUserId(userId);
        if (result.success() && result.getData() != null) {
            List<ChatRoomVO> voList = result.getData().stream().map(room -> {
                ChatRoomVO vo = new ChatRoomVO();
                BeanUtils.copyProperties(room, vo);
                return vo;
            }).collect(Collectors.toList());
            return ListResult.of(voList);
        }
        return ListResult.empty();
    }

    /**
     * Save message to chat room
     */
    @PostMapping("/message/save")
    public DataResult<Long> saveMessage(@RequestBody MessageSaveRequest request) {
        AiChatMessageCreateParam param = new AiChatMessageCreateParam();
        param.setChatRoomId(request.getChatRoomId());
        param.setRole(request.getRole());
        param.setContent(request.getContent());
        param.setUserId(request.getUserId());
        DataResult<Long> created = aiChatMessageService.create(param);

        List<Long> attachmentIds = request.getAttachmentIds();
        if (created.success() && created.getData() != null
                && attachmentIds != null && !attachmentIds.isEmpty()) {
            Long messageId = created.getData();
            chatAttachmentService.linkAttachmentsToMessage(messageId, attachmentIds);
            // Make sure the attachment shows up in the room's library
            // even when the user uploaded it before picking a room
            // (chat_room_id may have been null at upload time).
            Long userId = request.getUserId() != null ? request.getUserId() : ContextUtils.getUserId();
            if (userId != null && request.getChatRoomId() != null) {
                for (Long aid : attachmentIds) {
                    chatAttachmentService.bindToRoom(userId, aid, request.getChatRoomId());
                }
            }
        }
        return created;
    }

    /**
     * Update message content
     */
    @PostMapping("/message/update")
    public ActionResult updateMessage(@RequestBody MessageUpdateRequest request) {
        AiChatMessageUpdateParam param = new AiChatMessageUpdateParam();
        param.setId(request.getId());
        param.setContent(request.getContent());
        return aiChatMessageService.update(param);
    }

    /**
     * Get messages by chat room id. Hydrates attachment metadata
     * inline using two batched queries (message_attachment join +
     * attachment meta), so the cost stays O(2) regardless of how
     * deep the conversation is.
     */
    @GetMapping("/message/list/{chatRoomId}")
    public ListResult<MessageVO> listMessages(@PathVariable Long chatRoomId) {
        ListResult<AiChatMessage> result = aiChatMessageService.listByChatRoomId(chatRoomId);
        if (!result.success() || result.getData() == null) {
            return ListResult.empty();
        }
        List<AiChatMessage> messages = result.getData();
        List<MessageVO> voList = messages.stream().map(message -> {
            MessageVO vo = new MessageVO();
            BeanUtils.copyProperties(message, vo);
            return vo;
        }).collect(Collectors.toList());

        hydrateAttachments(voList);
        return ListResult.of(voList);
    }

    /**
     * Resolve the {@code attachments} field on every {@link MessageVO}
     * with one mapping query + one bulk attachment-meta query. Order
     * within a message follows the original {@code position} stored at
     * link time.
     */
    private void hydrateAttachments(List<MessageVO> voList) {
        if (voList == null || voList.isEmpty()) {
            return;
        }
        List<Long> messageIds = voList.stream()
                .map(MessageVO::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (messageIds.isEmpty()) return;

        AiChatMessageAttachmentMapper linkMapper =
                Dbutils.getMapper(AiChatMessageAttachmentMapper.class);
        List<AiChatMessageAttachmentMapper.Mapping> mappings = linkMapper.findByMessageIds(messageIds);
        if (mappings == null || mappings.isEmpty()) return;

        Long userId = ContextUtils.getUserId();
        if (userId == null) return;

        List<Long> attachmentIds = mappings.stream()
                .map(AiChatMessageAttachmentMapper.Mapping::getAttachmentId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, AttachmentMetaDTO> metaById = new HashMap<>();
        for (AiChatAttachmentDO row : chatAttachmentService.findMetaByIds(userId, attachmentIds)) {
            metaById.put(row.getId(), AttachmentMetaDTO.from(row));
        }

        // Group mapping rows back under their message id, preserving
        // the (position, attachment_id) order returned by the mapper.
        Map<Long, List<AttachmentMetaDTO>> byMessage = new LinkedHashMap<>();
        for (AiChatMessageAttachmentMapper.Mapping m : mappings) {
            AttachmentMetaDTO meta = metaById.get(m.getAttachmentId());
            if (meta == null) continue;
            byMessage.computeIfAbsent(m.getMessageId(), k -> new ArrayList<>()).add(meta);
        }

        for (MessageVO vo : voList) {
            List<AttachmentMetaDTO> list = byMessage.get(vo.getId());
            vo.setAttachments(list != null ? list : Collections.emptyList());
        }
    }
}



