/**
 * Notification store - Svelte 5 Runes (replaces Zustand notification store)
 */

export type NotificationType = 'success' | 'error' | 'info' | 'warning';

export interface Notification {
	id: string;
	type: NotificationType;
	title: string;
	message?: string;
	duration?: number;
	dismissible?: boolean;
}

let notifications = $state<Notification[]>([]);
let notificationId = 0;

export function getNotificationStore() {
	return {
		get notifications() { return notifications; }
	};
}

export function addNotification(notification: Omit<Notification, 'id'>): string {
	const id = `notification-${++notificationId}`;
	const newNotification: Notification = {
		...notification,
		id,
		dismissible: notification.dismissible ?? true,
		duration: notification.duration ?? 5000
	};

	notifications = [...notifications, newNotification];

	if (newNotification.duration && newNotification.duration > 0) {
		setTimeout(() => removeNotification(id), newNotification.duration);
	}

	return id;
}

export function removeNotification(id: string) {
	notifications = notifications.filter((n) => n.id !== id);
}

export function clearAllNotifications() {
	notifications = [];
}

export const notify = {
	success: (title: string, message?: string) =>
		addNotification({ type: 'success', title, message }),
	error: (title: string, message?: string) =>
		addNotification({ type: 'error', title, message, duration: 8000 }),
	info: (title: string, message?: string) =>
		addNotification({ type: 'info', title, message }),
	warning: (title: string, message?: string) =>
		addNotification({ type: 'warning', title, message })
};
