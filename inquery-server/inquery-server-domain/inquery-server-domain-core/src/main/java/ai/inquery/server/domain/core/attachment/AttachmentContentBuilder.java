package ai.inquery.server.domain.core.attachment;

import ai.inquery.server.domain.core.attachment.ModelCapabilities.Capability;
import ai.inquery.server.domain.repository.entity.AiChatAttachmentDO;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Bridges {@link AiChatAttachmentService} into LangChain4j-shaped input.
 *
 * <p>Responsibilities:
 * <ol>
 *     <li>Convert a list of attachment ids into the ordered
 *         {@code List<Content>} the AiService method expects.</li>
 *     <li>Decide the effective model name when one of the attachments
 *         requires a capability the requested model doesn't have —
 *         this is the silent auto-switch path described in the design
 *         (e.g. PDF attached while user picked gpt-5.4-nano →
 *         transparently bump to gpt-5.4-mini).</li>
 * </ol>
 *
 * <p>The builder is intentionally stateless: callers should hold onto
 * the returned {@link Built} record and treat it as the source of
 * truth for both the chat-time content list and the SSE
 * {@code model_switched} event payload.
 */
@Slf4j
public final class AttachmentContentBuilder {

    private AttachmentContentBuilder() {}

    public record Built(
            List<Content> contents,
            String effectiveModel,
            ModelSwitch modelSwitch,
            List<AiChatAttachmentDO> resolvedAttachments) {}

    public record ModelSwitch(String from, String to, String reason) {}

    /**
     * Build chat-time content + (optionally) switch the model.
     *
     * @param attachmentService service used to load BYTEA / text payloads
     * @param userId            owner of the attachments (enforced)
     * @param attachmentIds     ordered ids (may be {@code null}/empty)
     * @param requestedModel    the model the user originally picked
     * @return Built result; never {@code null}, with an empty
     *         {@code contents} list when no attachments are supplied
     */
    public static Built build(AiChatAttachmentService attachmentService,
                              Long userId,
                              List<Long> attachmentIds,
                              String requestedModel) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return new Built(Collections.emptyList(), requestedModel, null, Collections.emptyList());
        }
        if (attachmentIds.size() > AiChatAttachmentService.MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new IllegalArgumentException("Too many attachments: max "
                    + AiChatAttachmentService.MAX_ATTACHMENTS_PER_MESSAGE);
        }

        List<AiChatAttachmentDO> metas = attachmentService.findMetaByIds(userId, attachmentIds);
        // Preserve the caller-supplied order even when the bulk fetch
        // returns rows in a different order (mybatis-plus IN-list does
        // not preserve sequence).
        List<AiChatAttachmentDO> ordered = new ArrayList<>(attachmentIds.size());
        for (Long id : attachmentIds) {
            for (AiChatAttachmentDO m : metas) {
                if (Objects.equals(m.getId(), id)) {
                    ordered.add(m);
                    break;
                }
            }
        }
        if (ordered.isEmpty()) {
            return new Built(Collections.emptyList(), requestedModel, null, Collections.emptyList());
        }

        EnumSet<Capability> required = EnumSet.noneOf(Capability.class);
        for (AiChatAttachmentDO a : ordered) {
            switch (a.getKind()) {
                case "image" -> required.add(Capability.IMAGE);
                case "pdf"   -> required.add(Capability.PDF);
                case "office", "text" -> { /* delivered as inline text — every model handles it */ }
                default      -> { /* ignore unknown kinds */ }
            }
        }

        // Pick effective model. We only switch when the requested model
        // is *known* to be missing a required capability. If the caller
        // hands us a model id we don't recognise (placeholder names like
        // `inquery-agent`, future model versions, custom deployments,
        // etc.), trust the caller and pass attachments through — the
        // worst case is that the LLM itself rejects the payload, which
        // is strictly better than blocking a perfectly valid request
        // because our allow-list is stale. The switch never leaves the
        // original provider — see ModelCapabilities.pickFallbackInSameProvider().
        String effective = requestedModel;
        ModelSwitch modelSwitch = null;
        for (Capability cap : required) {
            // Unknown to the matrix → let it through (caller knows best).
            if (!ModelCapabilities.isKnown(effective)) {
                log.debug("Skipping capability check for unknown model '{}' (cap={})",
                        effective, cap);
                continue;
            }
            if (ModelCapabilities.supports(effective, cap)) continue;
            String fallback = ModelCapabilities.pickFallbackInSameProvider(effective, cap);
            if (fallback == null) {
                // Known model + known provider but no in-provider fallback
                // (e.g. user pinned the only model in that provider that
                // can't handle PDFs). Pass through and let the LLM decide
                // rather than blocking outright; we still warn.
                log.warn("No same-provider fallback for {} on {}; passing through",
                        cap, effective);
                continue;
            }
            log.info("Attachment capability mismatch: model={} lacks {} → switching to {}",
                    effective, cap, fallback);
            modelSwitch = new ModelSwitch(
                    requestedModel,
                    fallback,
                    cap.name().toLowerCase() + "_attachment");
            effective = fallback;
        }

        // Materialise Content list. Images and PDFs ride as base64; text
        // attachments are inlined as TextContent so the model sees them
        // as part of the same UserMessage rather than as a separate
        // turn (which would burn extra tool-call budget).
        List<Content> contents = new ArrayList<>(ordered.size());
        for (AiChatAttachmentDO a : ordered) {
            switch (a.getKind()) {
                case "image" -> {
                    byte[] bytes = attachmentService.loadContentInternal(a.getId());
                    if (bytes == null) {
                        log.warn("Skipping image attachment {} — content not found", a.getId());
                        continue;
                    }
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    contents.add(ImageContent.from(b64, a.getMimeType()));
                }
                case "pdf" -> {
                    byte[] bytes = attachmentService.loadContentInternal(a.getId());
                    if (bytes == null) {
                        log.warn("Skipping pdf attachment {} — content not found", a.getId());
                        continue;
                    }
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    contents.add(PdfFileContent.from(b64, a.getMimeType()));
                }
                case "office", "text" -> {
                    String txt = attachmentService.loadExtractedTextInternal(a.getId());
                    if (txt == null || txt.isBlank()) {
                        log.warn("Attachment {} ({}) has no extracted text; passing explicit notice to model",
                                a.getId(), a.getFilename());
                        String notice = "Attached file: " + safe(a.getFilename())
                                + "\n[No extractable text was available for this file. "
                                + "If this is an Office presentation/document, it may contain only images, "
                                + "charts, or unsupported embedded objects.]";
                        contents.add(TextContent.from(notice));
                        continue;
                    }
                    // Tag the inline file so the model knows which
                    // payload it's reading when the user attaches more
                    // than one.
                    String labelled = "Attached file: " + safe(a.getFilename())
                            + "\n----- BEGIN -----\n" + txt + "\n----- END -----";
                    contents.add(TextContent.from(labelled));
                }
                default -> log.debug("Skipping attachment {} with unknown kind {}", a.getId(), a.getKind());
            }
        }

        return new Built(contents, effective, modelSwitch, ordered);
    }

    private static String safe(String s) {
        if (s == null) return "(untitled)";
        return s.replace("\n", " ").replace("\r", " ");
    }
}
