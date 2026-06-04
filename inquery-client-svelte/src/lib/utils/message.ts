/**
 * Simple toast message utility (replaces antd message)
 * TODO: Replace with proper toast component (shadcn-svelte Sonner)
 */

type MessageType = 'success' | 'error' | 'warning' | 'info';

function showMessage(type: MessageType, content: string, duration = 3000) {
	if (typeof window === 'undefined') return;

	const container = document.getElementById('toast-container') || createToastContainer();
	const toast = document.createElement('div');

	const colors: Record<MessageType, string> = {
		success: 'bg-green-500',
		error: 'bg-red-500',
		warning: 'bg-yellow-500',
		info: 'bg-blue-500'
	};

	toast.className = `${colors[type]} text-white px-4 py-2 rounded-lg shadow-lg mb-2 text-sm animate-in fade-in slide-in-from-top-2 duration-200`;
	toast.textContent = content;
	container.appendChild(toast);

	setTimeout(() => {
		toast.classList.add('animate-out', 'fade-out');
		setTimeout(() => toast.remove(), 200);
	}, duration);
}

function createToastContainer() {
	const container = document.createElement('div');
	container.id = 'toast-container';
	container.className = 'fixed top-4 right-4 z-[9999] flex flex-col items-end';
	document.body.appendChild(container);
	return container;
}

const message = {
	success: (content: string) => showMessage('success', content),
	error: (content: string) => showMessage('error', content),
	warning: (content: string) => showMessage('warning', content),
	info: (content: string) => showMessage('info', content)
};

export default message;
