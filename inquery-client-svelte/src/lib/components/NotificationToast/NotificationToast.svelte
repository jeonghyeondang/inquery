<script lang="ts">
	import { X, CheckCircle2, AlertCircle, Info, AlertTriangle } from 'lucide-svelte';
	import { getNotificationStore, removeNotification, type NotificationType } from '$lib/stores/notification.svelte';

	const store = getNotificationStore();

	const iconMap: Record<NotificationType, typeof CheckCircle2> = {
		success: CheckCircle2,
		error: AlertCircle,
		info: Info,
		warning: AlertTriangle,
	};

	const colorMap: Record<NotificationType, string> = {
		success: 'text-green-500',
		error: 'text-red-500',
		info: 'text-blue-500',
		warning: 'text-yellow-500',
	};

	const bgMap: Record<NotificationType, string> = {
		success: 'border-green-500/20',
		error: 'border-red-500/20',
		info: 'border-blue-500/20',
		warning: 'border-yellow-500/20',
	};
</script>

{#if store.notifications.length > 0}
	<div class="fixed top-4 right-4 z-[100] flex flex-col gap-2 max-w-sm">
		{#each store.notifications as notification (notification.id)}
			{@const Icon = iconMap[notification.type]}
			<div
				class="flex items-start gap-3 rounded-lg border bg-background shadow-lg p-3 animate-in slide-in-from-right-5 {bgMap[notification.type]}"
				role="alert"
			>
				<Icon size={18} class="{colorMap[notification.type]} shrink-0 mt-0.5" />
				<div class="flex-1 min-w-0">
					<p class="text-sm font-medium text-foreground">{notification.title}</p>
					{#if notification.message}
						<p class="text-xs text-muted-foreground mt-0.5">{notification.message}</p>
					{/if}
				</div>
				{#if notification.dismissible}
					<button
						class="p-0.5 rounded hover:bg-accent text-muted-foreground shrink-0"
						onclick={() => removeNotification(notification.id)}
					>
						<X size={14} />
					</button>
				{/if}
			</div>
		{/each}
	</div>
{/if}
