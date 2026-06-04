import type { IUserVO } from '$lib/types/user';
import { getUser } from '$lib/service/user';

/**
 * User store - Svelte 5 Runes (replaces Zustand useUserStore)
 */
let curUser = $state<IUserVO | null | undefined>(null);

export function getUserStore() {
	return {
		get curUser() {
			return curUser;
		},
		setCurUser(user: IUserVO | null | undefined) {
			curUser = user;
		}
	};
}

/** Fetch and set current user */
export async function queryCurUser(): Promise<IUserVO | undefined> {
	try {
		const user = (await getUser()) || undefined;
		curUser = user;
		// Write user id to cookie
		if (typeof document !== 'undefined' && user?.id) {
			const date = new Date('2030-12-30 12:30:00').toUTCString();
			document.cookie = `INQUERY.USER_ID=${user.id};Expires=${date}`;
		}
		return user;
	} catch {
		curUser = undefined;
		return undefined;
	}
}
