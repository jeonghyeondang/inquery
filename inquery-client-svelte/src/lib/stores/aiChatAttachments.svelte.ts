/**
 * Glue between message-bubble cards and the chat composer.
 *
 * `AttachmentCardList` lives deep inside the chat scroll, while
 * `pendingAttachments` lives on the `/ai-chat/+page.svelte` route. To
 * avoid prop drilling, the route registers a callback here at mount
 * time; the card list reads it through `getReattachHandler()` and
 * invokes it when the user picks "Re-attach to new message".
 */
import type { IAttachment } from '$lib/service/attachment';

type ReattachHandler = (att: IAttachment) => void;

let handler: ReattachHandler | null = null;

export function setReattachHandler(h: ReattachHandler | null) {
	handler = h;
}

export function getReattachHandler(): ReattachHandler | null {
	return handler;
}
