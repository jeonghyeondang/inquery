import axios, { type AxiosRequestConfig, type AxiosError } from 'axios';
import { env } from '$env/dynamic/public';
import message from '$lib/utils/message';

export type IErrorLevel = 'toast' | 'prompt' | 'critical' | false;
export interface IOptions {
	method?: 'get' | 'post' | 'put' | 'delete';
	mock?: boolean;
	errorLevel?: IErrorLevel;
	delayTime?: number | true;
	outside?: boolean;
	isFullPath?: boolean;
	dynamicUrl?: boolean;
}

const codeMessage: Record<number, string> = {
	200: 'The server successfully returned the requested data.',
	201: 'Data created or updated successfully.',
	400: 'The request contains an error; no data was created or modified.',
	401: 'Unauthorized access (invalid token, username, or password).',
	403: 'You are authorized but access is forbidden.',
	404: 'The requested record does not exist.',
	500: 'Server error. Please check the server.',
	502: 'Bad gateway.',
	503: 'Service unavailable.',
	504: 'Gateway timeout.'
};

enum ErrorCode {
	NEED_LOGGED_IN = 'common.needLoggedIn'
}

const noNeedToastErrorCode = [ErrorCode.NEED_LOGGED_IN];

const isTauri = () =>
	typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

export const getBaseURL = () => {
	if (typeof window === 'undefined') return env.PUBLIC_API_BASE_URL || '';
	const storedBaseURL = localStorage.getItem('_BaseURL');
	if (storedBaseURL) return storedBaseURL;
	if (isTauri()) return 'http://localhost:10821';
	return env.PUBLIC_API_BASE_URL || '';
};

// Create axios instance
const axiosInstance = axios.create({
	withCredentials: true,
	headers: {
		'Content-Type': 'application/json',
		Accept: 'application/json'
	}
});

// Request interceptor - attach JWT token
axiosInstance.interceptors.request.use((config) => {
	if (typeof window !== 'undefined') {
		const token = localStorage.getItem('Inquery');
		if (token) {
			config.headers.Inquery = token;
		}
	}
	return config;
});

// Response interceptor - handle token refresh & auth redirect
axiosInstance.interceptors.response.use(
	(response) => {
		const token = response.headers['inquery'];
		if (token && typeof window !== 'undefined') {
			localStorage.setItem('Inquery', token);
		}
		const { errorCode } = response.data || {};
		if (errorCode === ErrorCode.NEED_LOGGED_IN && typeof window !== 'undefined') {
			window.location.href = '/login';
		}
		return response;
	},
	(error) => Promise.reject(error)
);

const errorHandler = (error: AxiosError, errorLevel: IErrorLevel) => {
	const { response } = error;
	if (!response) return;
	const errorText = codeMessage[response.status] || response.statusText;
	if (errorLevel === 'toast') {
		message.error(`${response.status}: ${errorText}`);
	}
};

function delayTimeFn(callback: () => void, time: number | true | undefined) {
	if (time) {
		const timer = setTimeout(() => {
			callback();
			clearInterval(timer);
		}, typeof time === 'number' ? time : 500);
	} else {
		callback();
	}
}

export default function createRequest<P = void, R = void>(url: string, options?: IOptions) {
	const {
		method = 'get',
		errorLevel = 'toast',
		delayTime,
		outside,
		isFullPath,
		dynamicUrl
	} = options || {};

	return function (params: P, restParams?: AxiosRequestConfig): Promise<R> {
		const _baseURL = getBaseURL();

		// Replace URL parameters (e.g., :id)
		const paramsInUrl: string[] = [];
		const _url = url.replace(/:(.+?)\b/, (_, name: string) => {
			const value = (params as Record<string, unknown>)[name];
			paramsInUrl.push(name);
			return `${value}`;
		});

		if (paramsInUrl.length && params) {
			paramsInUrl.forEach((name) => {
				delete (params as Record<string, unknown>)[name];
			});
		}

		return new Promise<R>((resolve, reject) => {
			let eventualUrl = outside ? _url : `${_baseURL}${_url}`;
			eventualUrl = isFullPath ? url : eventualUrl;
			if (dynamicUrl) eventualUrl = params as unknown as string;

			const config: AxiosRequestConfig = {
				method,
				url: eventualUrl,
				...restParams
			};

			if (method === 'get' || method === 'delete') {
				config.params = params;
			} else {
				config.data = params;
			}

			axiosInstance(config)
				.then((response) => {
					const res = response.data;
					if (!res) return;
					const { success, errorCode, errorMessage, errorDetail, solutionLink, data } = res;

					if (
						!success &&
						errorLevel === 'toast' &&
						!noNeedToastErrorCode.includes(errorCode)
					) {
						delayTimeFn(() => {
							message.error(errorMessage || 'Request failed');
						const error = new Error(errorMessage || 'Request failed');
						(error as unknown as Record<string, unknown>).errorCode = errorCode;
						(error as unknown as Record<string, unknown>).errorMessage = errorMessage;
							reject(error);
						}, delayTime);
						return;
					}

					delayTimeFn(() => resolve(data), delayTime);
				})
				.catch((error) => {
					delayTimeFn(() => {
						errorHandler(error, errorLevel);
						reject(error);
					}, delayTime);
				});
		});
	};
}
