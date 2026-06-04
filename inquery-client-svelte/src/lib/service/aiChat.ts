import createRequest from './base';
import type { IAttachment } from './attachment';

export interface IChatRoom {
	id: number;
	conversationId: string;
	title: string;
	userId: number;
	gmtCreate: string;
	gmtModified: string;
}

/** Surfaced to the chat bubble when the server auto-switches model. */
export interface IModelSwitched {
	from: string;
	to: string;
	reason: string;
}

export interface IChatMessage {
	id: number;
	chatRoomId: number;
	role: 'user' | 'assistant';
	content: string;
	userId: number;
	gmtCreate: string;
	/** Hydrated by the backend list endpoint via N:N join. */
	attachments?: IAttachment[];
	/** Client-only badge populated when the server emits {@code model_switched}. */
	modelSwitched?: IModelSwitched;
}

export const createChatRoom = createRequest<{ conversationId: string; title: string; userId: number }, number>('/api/ai/chat-room/create', { method: 'post' });
export const updateChatRoom = createRequest<{ id: number; title: string }, void>('/api/ai/chat-room/update', { method: 'post' });
export const deleteChatRoom = createRequest<{ id: number }, void>('/api/ai/chat-room/delete/:id', { method: 'delete' });
export const listChatRooms = createRequest<{ userId: number }, IChatRoom[]>('/api/ai/chat-room/list/:userId');
export const saveMessage = createRequest<{ chatRoomId: number; role: string; content: string; userId: number; attachmentIds?: number[] }, number>('/api/ai/chat-room/message/save', { method: 'post' });
export const updateMessage = createRequest<{ id: number; content: string }, void>('/api/ai/chat-room/message/update', { method: 'post' });
export const getMessagesByChatRoomId = createRequest<{ chatRoomId: number }, IChatMessage[]>('/api/ai/chat-room/message/list/:chatRoomId');

export type FeedbackType = 'POSITIVE' | 'NEGATIVE';
export const submitFeedback = createRequest<Record<string, unknown>, number>('/api/ai/feedback', { method: 'post' });
