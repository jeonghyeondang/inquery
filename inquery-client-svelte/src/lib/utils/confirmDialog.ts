/**
 * Custom confirm dialog utility (replaces native confirm()).
 * Returns a Promise<boolean> - true if confirmed, false if cancelled.
 */

interface ConfirmOptions {
	title?: string;
	message: string;
	confirmText?: string;
	cancelText?: string;
	variant?: 'default' | 'destructive';
}

export default function confirmDialog(options: ConfirmOptions): Promise<boolean> {
	return new Promise((resolve) => {
		const overlay = document.createElement('div');
		overlay.className = 'fixed inset-0 z-[9999] flex items-center justify-center';

		const backdrop = document.createElement('div');
		backdrop.className = 'fixed inset-0 bg-black/50 animate-in fade-in-0';

		const card = document.createElement('div');
		card.className =
			'relative z-50 w-full max-w-sm border border-border bg-card p-6 shadow-lg rounded-lg animate-in fade-in-0 zoom-in-95';

		const title = options.title || 'Confirm';
		const isDestructive = options.variant === 'destructive';

		card.innerHTML = `
			<h3 class="text-base font-semibold text-foreground mb-2">${title}</h3>
			<p class="text-sm text-muted-foreground mb-5">${options.message}</p>
			<div class="flex justify-end gap-2">
				<button data-action="cancel"
					class="inline-flex items-center justify-center rounded-md text-sm font-medium h-9 px-4 border border-input bg-background hover:bg-accent hover:text-accent-foreground transition-colors">
					${options.cancelText || 'Cancel'}
				</button>
				<button data-action="confirm"
					class="inline-flex items-center justify-center rounded-md text-sm font-medium h-9 px-4 text-white transition-colors ${isDestructive ? 'bg-destructive hover:bg-destructive/90' : 'bg-primary hover:bg-primary/90'}">
					${options.confirmText || 'Confirm'}
				</button>
			</div>
		`;

		function cleanup(result: boolean) {
			overlay.classList.add('animate-out', 'fade-out');
			setTimeout(() => overlay.remove(), 150);
			resolve(result);
		}

		backdrop.addEventListener('click', () => cleanup(false));
		card.querySelector('[data-action="cancel"]')?.addEventListener('click', () => cleanup(false));
		card.querySelector('[data-action="confirm"]')?.addEventListener('click', () => cleanup(true));

		function handleKeydown(e: KeyboardEvent) {
			if (e.key === 'Escape') {
				cleanup(false);
				document.removeEventListener('keydown', handleKeydown);
			}
		}
		document.addEventListener('keydown', handleKeydown);

		overlay.appendChild(backdrop);
		overlay.appendChild(card);
		document.body.appendChild(overlay);
	});
}
