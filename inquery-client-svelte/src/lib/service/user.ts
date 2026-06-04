import createRequest from './base';
import type { IUserVO } from '$lib/types/user';

/** User login */
export const login = createRequest<{ userName: string; password: string }, boolean>(
	'/api/oauth/login_a',
	{ method: 'post' }
);

/** User logout */
export const logout = createRequest<void, void>('/api/oauth/logout_a', { method: 'post' });
export const userLogout = logout;

/** Get current user info */
export const getUser = createRequest<void, IUserVO | null>('/api/oauth/user_a', { method: 'get' });

/**
 * Self change-password. The currently authenticated user provides their
 * existing password and a new one; the backend verifies the current password
 * (bcrypt) and persists the new bcrypt hash.
 *
 * `errorLevel: false` so the Settings UI can render inline error messages
 * (incorrect current password, etc.) without surfacing a global toast.
 */
export const changePassword = createRequest<
	{ currentPassword: string; newPassword: string },
	void
>('/api/oauth/change-password', { method: 'post', errorLevel: false });
